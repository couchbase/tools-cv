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

 @Library('cvutils')

import hudson.model.Result
import hudson.model.Run
import jenkins.model.CauseOfInterruption.UserInterruption

SILENT = env.JOB_NAME.contains("silent")

PARALLELISM = 16

GO_VERSION = "1.19"
CB_SERVER_MANIFEST = "branch-master.xml"
CB_SERVER_MANIFEST_GROUPS = "backup"

WINDOWS_NODE_LABEL = "msvc2017"
LINUX_NODE_LABEL = "ubuntu-18.04 && large"
MACOS_NODE_LABEL = "kv-macos"

CMAKE_ARGS = "-DBUILD_ENTERPRISE=1"

RUN_EXAMINADOR = false

pipeline {
    agent { label cvutils.getNodeLabel() }

    environment {
        EXAMINADOR = "${WORKSPACE}/examinador"

        CB_SERVER_SOURCE = "${WORKSPACE}/server"
        CB_SERVER_SOURCE_PROJECT = "${CB_SERVER_SOURCE}/${GERRIT_PROJECT}"

        GOLANGCI_LINT_VERSION = "v1.49.0"

        GOROOT = "${WORKSPACE}/go"
        TEMP_GOBIN = "${GOROOT}/bin"

        PATH="${PATH}:${TEMP_GOBIN}:${WORKSPACE}/bin"
    }

    stages {
        stage("Setup") {
            steps {
                script {
                    cvutils.configGerritTrigger()
                    cvutils.checkRequiredEnvars()

                    // default CMAKE_ARGS to an empty string if unset
                    env.CMAKE_ARGS = env.CMAKE_ARGS ?: ""

                    if (cvutils.getJobName() != "windows") {
                        env.CMAKE_ARGS="-DCMAKE_BUILD_TYPE=DebugOptimized ${CMAKE_ARGS}"
                    }

                    // Constrain link parallelism to half of the compile
                    // parallelism given that linking is typically much more RAM
                    // hungry than compilation, and we have seen the build get
                    // OOM-killed on machines which have many cores but lower
                    // RAM (e.g. 16 cores, 16 GB RAM).
                    if (env.PARALLELISM) {
                       link_parallelism = ((env.PARALLELISM as Integer) / 2)
                       env.CMAKE_ARGS="${CMAKE_ARGS} -DCB_PARALLEL_LINK_JOBS=${link_parallelism}"
                    }

                    env.CMAKE_GENERATOR = env.CMAKE_GENERATOR ? env.CMAKE_GENERATOR : "Ninja"

                }
            }
        }

        stage("Install Go") {
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    script {
                        cvutils.installGo(GO_VERSION)
                    }
                }
            }
        }

        stage("Install CV Dependencies") {
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    script {
                        cvutils.installGoDeps(TEMP_GOBIN, GOLANGCI_LINT_VERSION)
                    }
                }
            }
        }

        stage("Tools Common Clone") {
            when {
                expression {
                    // Only run this step for 'tools-common', it has a slightly lighter weight build process.
                    return env.GERRIT_PROJECT == 'tools-common';
                }
            }
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    // Create the source directory and any missing parent directories
                    sh "mkdir -p ${CB_SERVER_SOURCE}"

                    // Perform a shallow clone of 'tools-common', the branch shouldn't matter here since we'll be
                    // switching to the 'FETCH_HEAD' branch.
                    sh "git clone --depth=1 ssh://buildbot@review.couchbase.org:29418/${GERRIT_PROJECT}.git ${CB_SERVER_SOURCE_PROJECT}"
                }
            }
        }

        stage("Clone") {
            when {
                expression {
                    // All projects except 'tools-common' use 'repo' to clone them so ensure this step doesn't execute
                    // for 'tools-common' as it has it's own clone step.
                    return env.GERRIT_PROJECT != 'tools-common';
                }
            }

            steps {
                timeout(time: 5, unit: "MINUTES") {
                    // Create the source directory and any missing parent directories
                    sh "mkdir -p ${CB_SERVER_SOURCE}"

                    // Initialize and sync 'backup' group using 'repo'
                    dir("${CB_SERVER_SOURCE}") {
                        script {
                            cvutils.repo(CB_SERVER_MANIFEST, CB_SERVER_MANIFEST_GROUPS, PARALLELISM)
                        }
                    }
                }
            }
        }

        stage("Checkout") {
            steps {
                // Fetch the commit we are testing
                dir("${CB_SERVER_SOURCE_PROJECT}") {
                    script {
                        cvutils.checkOutPatch()
                    }
                }
            }
        }

        // backup, tools-common
        stage("Lint") {
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    dir("${CB_SERVER_SOURCE_PROJECT}") {
                        script {
                            cvutils.runLint()
                        }
                    }
                }
                script {
                    if (env.GERRIT_PROJECT == 'cbmultimanager') {
                        timeout(time: 5, unit: "MINUTES") {
                            dir("${CB_SERVER_SOURCE_PROJECT}") {
                                sh "tools/licence-lint.sh"
                            }
                        }
                        timeout(time: 5, unit: "MINUTES") {
                            dir("${CB_SERVER_SOURCE_PROJECT}") {
                                sh "GOBIN=${TEMP_GOBIN} go run tools/validate-checker-docs.go"
                            }
                        } 
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
                dir("${CB_SERVER_SOURCE_PROJECT}") {
                    sh "./jenkins/adoc-lint.sh"
                }
            }
        }

        stage("Build") {
            when {
                expression {
                    return env.GERRIT_PROJECT == 'backup';
                }
            }
            steps {
                timeout(time: 10, unit: "MINUTES") {
                    dir("${CB_SERVER_SOURCE_PROJECT}") {
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
                script {
                    cvutils.runGoTests(TEMP_GOBIN, CB_SERVER_SOURCE_PROJECT, "-tags noui")
                }
            }
        }

        stage("Benchmark") {
            steps {
                dir("${CB_SERVER_SOURCE_PROJECT}") {
                    script {
                        extraArgs = ""
                        if (env.GERRIT_PROJECT == "cbmultimanager") {
                            extraArgs = "-tags noui"
                        }
                        // Run the benchmarks without running any tests by setting '-run='^$'
                        sh "go test -timeout=15m -count=1 -run='^\044' ${extraArgs} -bench=Benchmark -benchmem ./..."
                    }
                }
            }
        }
    

        stage("Get Couchbase Server Source") {
            when {
                expression {
                    return env.RUN_EXAMINADOR && env.GERRIT_PROJECT == 'backup';
                }
            }
            steps {
                timeout(time: 15, unit: "MINUTES") {
                    dir("${CB_SERVER_SOURCE}") {
                        script {
                            cvutils.repo(CB_SERVER_MANIFEST, "all", PARALLELISM)
                        }
                    }

                    // Fetch the commit we are testing
                    dir("${CB_SERVER_SOURCE_PROJECT}") {
                        // if no gerrit spec given run on the current head
                        script {
                            if (env.GERRIT_REFSPEC) {
                                cvutils.checkOutPatch()
                            }
                        }
                    }
                }
            }
        }

        stage("Setup Build Configuration") {
            when {
                expression {
                    return env.RUN_EXAMINADOR && env.GERRIT_PROJECT == 'backup';
                }
            }
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    dir("${CB_SERVER_SOURCE}") {
                        // If we've checked out a specific version of the tlm project
                        // then we'll need to bring our new CMakeLists.txt in manually
                        sh 'cp -f tlm/CMakeLists.txt CMakeLists.txt'
                        sh 'cp -f tlm/third-party-CMakeLists.txt third_party/CMakeLists.txt'
                        sh 'mkdir -p build'
                        sh 'cd build && cmake -G ${CMAKE_GENERATOR} ${CMAKE_ARGS} ..'
                    }
                }
            }
        }

        stage("Build Couchbase Server") {
            when {
                expression {
                    return env.RUN_EXAMINADOR && env.GERRIT_PROJECT == 'backup';
                }
            }
            steps {
                timeout(time: 60, unit: "MINUTES") {
                    dir("${CB_SERVER_SOURCE}") {
                        sh 'cmake --build build --parallel ${PARALLELISM} --target everything --target install'
                        sh 'ccache -s'
                    }
                }
            }
        }

        stage("Setup Examinador") {
            when {
                expression {
                    return env.RUN_EXAMINADOR && env.GERRIT_PROJECT == 'backup';
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
                    return env.RUN_EXAMINADOR && env.GERRIT_PROJECT == 'backup';
                }
            }
            steps{
                dir("${EXAMINADOR}") {
                    sh '''#!/bin/bash
                          source robot-env/bin/activate
                          robot-env/bin/robot --variable SOURCE:${CB_SERVER_SOURCE} --variable WORKSPACE:${WORKSPACE} --variable DELETE_LOGS:True --outputdir ${WORKSPACE}/reports --exclude in_progress --exclude UI --exclude wait_for_bug_fix --consolewidth 120 -L DEBUG cbm_tests cbm_multi_service_tests
                    '''
                }
            }
        }
    }


    post {
        always {
            script {
                cvutils.postTestResults()
                cvutils.postCoverage()
                if (env.RUN_EXAMINADOR && env.GERRIT_PROJECT == 'backup') {
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
        }

        cleanup {
            script {
                cvutils.cleanUp()
            }
        }
    }
}