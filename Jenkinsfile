#!/usr/bin/env groovy

/**
 * Copyright (C) Couchbase, Inc 2019 - All Rights Reserved
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
import cvutils.groovy

SILENT = env.JOB_NAME.contains("silent")

WINDOWS_NODE_LABEL = "msvc2015"
LINUX_NODE_LABEL = "ubuntu-18.04 && large"
MACOS_NODE_LABEL = "kv-macos"

pipeline {
    agent { label getNodeLabel() }

    environment {
        SOURCE = "${WORKSPACE}/source"

        CURRENT_PROJECT = "${SOURCE}/${GERRIT_PROJECT}"

        GO_TARBALL_URL = "https://golang.org/dl/go1.19.linux-amd64.tar.gz"
        GOLANGCI_LINT_VERSION = "v1.49.0"

        GOROOT = "${WORKSPACE}/go"
        GOBIN = "${GOROOT}/bin"

        PATH="${PATH}:${GOBIN}:${WORKSPACE}/bin"
    }

    stages {
        stage("Setup") {
            steps {
                script {
                    // Configure Gerrit Trigger
                    properties([pipelineTriggers([
                        gerrit(
                            serverName: "review.couchbase.org",
                            silentMode: false,
                            silentStartMode: false,
                            gerritProjects: [
                                [
                                    compareType: "PLAIN",
                                    disableStrictForbiddenFileVerification: false, 
                                    pattern: env.GERRIT_PROJECT,
                                    branches: [[ 
                                        compareType: "PLAIN", 
                                        pattern: env.BRANCH_NAME 
                                    ]]
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

                script {
                    // Ensure we have the relevant gerrit envars
                    requiredEnvVars = ['GERRIT_HOST',
                                       'GERRIT_PORT',
                                       'GERRIT_PROJECT',
                                       'GERRIT_PATCHSET_REVISION',
                                       'GERRIT_REFSPEC',
                                       'GERRIT_CHANGE_ID']

                    for (var in requiredEnvVars) {
                        if (!env.getProperty(var)){
                            error "Required environment variable '${var}' not set."
                        }
                    }
                }

                timeout(time: 5, unit: "MINUTES") {
                    // Install Golang locally
                    sh "wget -q -O- ${GO_TARBALL_URL} | tar xz"

                    // get golangci-lint binary
                    sh "curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/${GOLANGCI_LINT_VERSION}/install.sh | sh -s -- -b ${GOBIN} ${GOLANGCI_LINT_VERSION}"
                    sh "golangci-lint --version"

                    // Unit test reporting
                    sh "go install github.com/jstemmer/go-junit-report@latest"

                    // Coverage reporting
                    sh "go install github.com/axw/gocov/gocov@latest"
                    sh "go install github.com/AlekSi/gocov-xml@latest"

                    // Create the source directory and any missing parent directories
                    sh "mkdir -p ${SOURCE}"

                    // Perform a shallow clone of 'backup', the branch shouldn't matter here since we'll be switching to
                    // the 'FETCH_HEAD' branch.
                    sh "git clone --depth=1 git@github.com:couchbase/${GERRIT_PROJECT}.git ${CURRENT_PROJECT}"

                    // Fetch the commit we are testing
                    dir("${CURRENT_PROJECT}") {
                        sh "git fetch ssh://buildbot@review.couchbase.org:29418/${GERRIT_PROJECT} ${GERRIT_REFSPEC}"
                        sh "git checkout FETCH_HEAD"
                    }
                }
            }
        }

        // backup, tools-common
        stage("Lint") {
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    dir("${CURRENT_PROJECT}") {
                        sh "golangci-lint run --timeout 5m"
                    }
                }
            }
        }

        stage("Spell check docs") {
            when {
                expression {
                    return env.GERRIT_PROJECT == 'backup';
                }
            }
            steps {
                dir("${CURRENT_PROJECT}") {
                    sh "./jenkins/adoc-lint.sh"
                }
            }
        }

        stage("Build") {
            steps {
                timeout(time: 10, unit: "MINUTES") {
                    dir("${CURRENT_PROJECT}") {
                        // Build all the available tools, note that we skip 'jsonctx' which does not contain a main.
                        sh '''#!/bin/bash
                              for dir in $(ls ./cmd); do
                                  if [ $dir != 'jsonctx' ]; then
                                      go build -race -o ./out/$dir ./cmd/$dir
                                  fi
                              done
                        '''
                    }
                }
            }
        }

        stage("Test") {
            steps {
                // Create somewhere to store our coverage/test reports
                sh "mkdir -p reports"

                dir("${CURRENT_PROJECT}") {
                    // Clean the Go test cache
                    sh "go clean -testcache"

                    // Run the unit testing
                    sh "2>&1 go test -v -timeout=15m -count=1 -coverprofile=coverage-backup.out ./... | tee ${WORKSPACE}/reports/test-backup.raw"

                    // Convert the test output into valid 'junit' xml
                    sh "cat ${WORKSPACE}/reports/test-backup.raw | go-junit-report > ${WORKSPACE}/reports/test-backup.xml"

                    // Convert the coverage report into valid 'cobertura' xml
                    sh "gocov convert coverage-backup.out | gocov-xml > ${WORKSPACE}/reports/coverage-backup.xml"
                }
            }
        }

        stage("Benchmark") {
            steps {
                dir("${CURRENT_PROJECT}") {
                    // Run the benchmarks without running any tests by setting '-run='^$'
                    sh "go test -timeout=15m -count=1 -run='^\044' -bench=Benchmark -benchmem ./..."
                }
            }
        }
    

        stage("Get CB") {
            steps {
                timeout(time: 15, unit: "MINUTES") {
                    dir("${SOURCE}") {
                        sh "repo init -u https://github.com/couchbase/manifest -m branch-master.xml -g all"
                        sh "repo sync -j8"
                    }

                    // Fetch the commit we are testing
                    dir("${CURRENT_PROJECT}") {
                        // if no gerrit spec given run on the current head
                        script {
                            if (env.GERRIT_REFSPEC) {
                                sh "git fetch ssh://buildbot@review.couchbase.org:29418/${GERRIT_PROJECT} ${GERRIT_REFSPEC}"
                                sh "git checkout FETCH_HEAD"
                            }
                        }
                    }
                }
            }
        }

        stage("Build Couchbase Server") {
            when {
                expression {
                    return env.GERRIT_PROJECT == 'backup';
                }
            }
            steps {
                timeout(time: 60, unit: "MINUTES") {
                    dir("${SOURCE}") {
                        sh "make -j8"
                    }
                }
            }
        }

        stage("Setup Examinador") {
            when {
                expression {
                    return env.GERRIT_PROJECT == 'backup';
                }
            }
            steps {
                dir("${WORKSPACE}") {
                    sh "git clone git@github.com:couchbaselabs/examinador.git"
                }

                dir("${EXAMINADOR}") {
                    // Use python virtual environment to make life easier
                    sh '''#!/bin/bash
                          python3 -m venv robot-env
                          source robot-env/bin/activate
                          pip install -r requirements.txt
                    '''
                }
            }
        }

        stage("Examinador Test") {
            when {
                expression {
                    return env.GERRIT_PROJECT == 'backup';
                }
            }
            steps{
                dir("${EXAMINADOR}") {
                    sh '''#!/bin/bash
                          source robot-env/bin/activate
                          robot-env/bin/robot --variable SOURCE:${SOURCE} --variable WORKSPACE:${WORKSPACE} --variable DELETE_LOGS:True --outputdir ${WORKSPACE}/reports --exclude in_progress --exclude UI --exclude wait_for_bug_fix --consolewidth 120 -L DEBUG cbm_tests cbm_multi_service_tests
                    '''
                }
            }
        }
    }


    post {
        always {
            // Post the test results
            junit allowEmptyResults: true, testResults: "reports/test-*.xml"

            // Post the test coverage
            cobertura autoUpdateStability: false, autoUpdateHealth: false, onlyStable: false, coberturaReportFile: "reports/coverage-*.xml", conditionalCoverageTargets: "70, 10, 30", failNoReports: false, failUnhealthy: true, failUnstable: true, lineCoverageTargets: "70, 10, 30", methodCoverageTargets: "70, 10, 30", maxNumberOfBuilds: 0, sourceEncoding: "ASCII", zoomCoverageChart: false
        }

        cleanup {
            // We don't need the build cache interfering with any subsequent builds
            sh "go clean --cache --testcache"

            // Remove the workspace
            deleteDir()
        }
    }
}


