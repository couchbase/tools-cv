#!/usr/bin/env groovy

/**
 * Copyright (C) Couchbase, Inc 2020 - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential.
 *
 * When updating this Jenkinsfile, changes will not take effect immediately; they will take effect once the Jenkins
 * multi-branch pipeline picks up the commit. This therefore means that changes made to the Jenkinsfile in a Gerrit
 * review will not have any effect until they are submitted.
 */

import hudson.model.Result
import hudson.model.Run
import jenkins.model.CauseOfInterruption.UserInterruption

pipeline {
    agent { label "ubuntu-18.04&&master&&large" }

    environment {
        PARALLELISM = 16
        CB_SERVER_MANIFEST = "branch-master.xml"
        SOURCE = "${WORKSPACE}/source"
        EXAMINADOR = "${WORKSPACE}/examinador"
    }

    stages {
        stage("Setup") {
            steps {
                script {
                    // Configure Gerrit Trigger
                    properties([pipelineTriggers([
                        gerrit(
                            serverName: "review.couchbase.org",
                            gerritProjects: [
                                [
                                    compareType: "PLAIN", disableStrictForbiddenFileVerification: false, pattern: "examinador",
                                    branches: [[ compareType: "PLAIN", pattern: env.BRANCH_NAME ]]
                                ],
                            ],
                            triggerOnEvents: [
                                commentAddedContains(commentAddedCommentContains: "reverify"),
                                draftPublished(),
                                patchsetCreated(excludeNoCodeChange: true)
                            ]
                        )
                    ])])
                }

                timeout(time: 10, unit: "MINUTES") {
                    // Install linter
                    timeout(time: 10, unit: "MINUTES") {
                        sh "pip3 install --user urllib3 pylint requests mypy"
                    }

                    // Initialise the build directory
                    dir("${WORKSPACE}") {
                        sh "git clone git@github.com:couchbaselabs/examinador.git"
                    }

                    // Fetch the commit we are testing
                    dir("${EXAMINADOR}") {
                        sh "git fetch ssh://buildbot@review.couchbase.org:29418/examinador ${GERRIT_REFSPEC}"
                        sh "git checkout FETCH_HEAD"
                    }
                }
            }
        }

        stage("Setup Examinador") {
            steps {
                dir("${EXAMINADOR}") {
                    // Use python virtual environment to make life easier
                    sh '''#!/bin/bash
                          python3 -m venv robot-env
                          source robot-env/bin/activate
                          pip3 install -r requirements.txt
                          pip3 install pylint mypy
                    '''

                    //Install chromedriver
                    sh '''#!/bin/bash
                          source robot-env/bin/activate
                          pip3 install webdrivermanager
                          webdrivermanager chrome --linkpath ${EXAMINADOR}/robot-env/bin
                    '''
                  }
            }
        }

        stage("Lint") {
            steps {
                timeout(time: 10, unit: "MINUTES") {
                    dir("${EXAMINADOR}") {
                        sh '''#!/bin/bash
                              source robot-env/bin/activate
                              python3 -m pylint --rcfile=${EXAMINADOR}/.pylintrc libraries
                        '''
                    }
                }
            }
        }

        stage("Type check") {
            steps {
                timeout(time: 10, unit: "MINUTES") {
                    dir("${EXAMINADOR}") {
                        sh """#!/bin/bash
                            source robot-env/bin/activate
                            if [ \$(mypy --ignore-missing-imports --implicit-optional libraries | grep -c error) -gt 1 ]; then
                                echo "Failed mypy type checking in libraries"
                                echo "Re running: mypy --ignore-missing-imports libraries"
                                echo \$(mypy --ignore-missing-imports --implicit-optional libraries)
                                exit 1
                            fi
                           """
                    }
                }
            }
        }

        stage("Get CB") {
            steps {
                timeout(time: 15, unit: "MINUTES") {
                    dir("${SOURCE}") {
                        sh "repo init -u git://github.com/couchbase/manifest -m ${CB_SERVER_MANIFEST} -g all"
                        sh "repo sync -j${PARALLELISM}"
                    }
                }
            }
        }

        stage("Build CB") {
            steps {
                timeout(time: 60, unit: "MINUTES") {
                    dir("${SOURCE}") {
                        sh "make -j${PARALLELISM}"
                    }
                }
            }
        }

        stage("Test") {
            steps{
                dir("${EXAMINADOR}") {
                    // Run test suites
                    sh '''#!/bin/bash
                          source robot-env/bin/activate
                          ulimit -n 9000
                          localstack start > /dev/null 2>&1 &
                          robot-env/bin/robot --variable SOURCE:${SOURCE} --variable WORKSPACE:${WORKSPACE} --listener RobotStackTracer --variable DELETE_LOGS:True --outputdir ${WORKSPACE}/reports --exclude in_progress --exclude wait_for_bug_fix --exclude UI --exclude broken_test --consolewidth 120 -L DEBUG *_tests rest_api_longer
                    '''
                }
            }
      }

    }

    post {
        always {
            // Post the test results
            script {
              step(
                    [
                      $class              : 'RobotPublisher',
                      outputPath          : 'reports',
                      outputFileName      : 'output.xml',
                      reportFileName      : 'report.html',
                      logFileName         : 'log.html',
                      otherFiles          : '*.zip',
                      disableArchiveOutput: false,
                      passThreshold       : 100,
                      unstableThreshold   : 95,
                    ]
                )
            }
        }

        cleanup {
            // Remove the workspace
            deleteDir()
        }
    }
}
