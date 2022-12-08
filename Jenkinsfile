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

SILENT = env.JOB_NAME.contains("silent")

PARALLELISM = 16

GO_VERSION = "1.19"
CB_SERVER_MANIFEST = "branch-master.xml"

WINDOWS_NODE_LABEL = "msvc2017"
LINUX_NODE_LABEL = "ubuntu-18.04 && large"
MACOS_NODE_LABEL = "kv-macos"

CMAKE_ARGS = "-DBUILD_ENTERPRISE=1"

RUN_EXAMINADOR = false

pipeline {
    agent { label getNodeLabel() }

    environment {
        EXAMINADOR = "${WORKSPACE}/examinador"

        CB_SERVER_SOURCE = "${WORKSPACE}/server"
        CB_SERVER_SOURCE_PROJECT = "${CB_SERVER_SOURCE}/${GERRIT_PROJECT}"

        GO_TARBALL_URL = "https://golang.org/dl/go1.19.linux-amd64.tar.gz"
        GOLANGCI_LINT_VERSION = "v1.49.0"

        GOROOT = "${WORKSPACE}/go"
        TEMP_GOBIN = "${GOROOT}/bin"

        PATH="${PATH}:${TEMP_GOBIN}:${WORKSPACE}/bin"
    }

    stages {
        stage("Setup") {
            steps {
                script {
                    sh 'printenv'
                    silentJob = false
                    if (getJobType() == "macos") {
                        silentJob = true
                    }
                    echo "JobName:${getJobName()} JobType:${getJobType()} silentJob:${silentJob}"
                    // Configure Gerrit Trigger
                    properties([pipelineTriggers([
                        gerrit(
                            serverName: "review.couchbase.org",
                            silentMode: silentJob,
                            silentStartMode: silentJob,
                            gerritProjects: [
                                [
                                    compareType: "PLAIN",
                                    disableStrictForbiddenFileVerification: false, 
                                    pattern: getProjectName(),
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

                    // default CMAKE_ARGS to an empty string if unset
                    env.CMAKE_ARGS = env.CMAKE_ARGS ?: ""

                    if (getJobName() != "windows") {
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
            timeout(time: 5, unit: "MINUTES") {
                // Install Golang locally
                sh "curl -sSL ${getGoDowloadURL()} | tar xz"
            }
        }

        stage("Install CV Dependencies") {
            timeout(time: 5, unit: "MINUTES") {
                // get golangci-lint binary
                sh "curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/${GOLANGCI_LINT_VERSION}/install.sh | sh -s -- -b ${TEMP_GOBIN} ${GOLANGCI_LINT_VERSION}"
                sh "golangci-lint --version"

                // Unit test reporting
                sh "GOBIN=${TEMP_GOBIN} go install github.com/jstemmer/go-junit-report@latest"

                // Coverage reporting
                sh "GOBIN=${TEMP_GOBIN} go install github.com/axw/gocov/gocov@latest"
                sh "GOBIN=${TEMP_GOBIN} go install github.com/AlekSi/gocov-xml@latest"
            }
        }

        stage("Clone") {
            when {
                expression {
                    return env.GERRIT_PROJECT == 'tools-common';
                }
            }

            timeout(time: 5, unit: "MINUTES") {
                // Create the source directory and any missing parent directories
                sh "mkdir -p ${CB_SERVER_SOURCE}"

                // Perform a shallow clone of 'tools-common', the branch shouldn't matter here since we'll be
                // switching to the 'FETCH_HEAD' branch.
                sh "git clone --depth=1 ssh://buildbot@review.couchbase.org:29418/${GERRIT_PROJECT}.git ${CB_SERVER_SOURCE_PROJECT}"
            }
        }

        stage("Clone") {
            when {
                expression {
                    return env.GERRIT_PROJECT != 'tools-common';
                }
            }

            timeout(time: 5, unit: "MINUTES") {
                // Create the source directory and any missing parent directories
                sh "mkdir -p ${CB_SERVER_SOURCE}"

                // Initialize and sync 'backup' group using 'repo'
                dir("${CB_SERVER_SOURCE}") {
                    sh "repo init -u https://github.com/couchbase/manifest -m ${CB_SERVER_MANIFEST} -g backup"
                    sh "repo sync --jobs=8"
                }
            }
        }

        stage("Checkout") {
            // Fetch the commit we are testing
            dir("${CB_SERVER_SOURCE_PROJECT}") {
                sh "git fetch ssh://buildbot@review.couchbase.org:29418/${GERRIT_PROJECT} ${GERRIT_REFSPEC}"
                sh "git checkout FETCH_HEAD"
            }
        }

        // backup, tools-common
        stage("Lint") {
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    dir("${CB_SERVER_SOURCE_PROJECT}") {
                        sh "GOBIN=${TEMP_GOBIN} golangci-lint run --timeout 5m"
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
                // Create somewhere to store our coverage/test reports
                sh "mkdir -p reports"

                dir("${CB_SERVER_SOURCE_PROJECT}") {
                    // Clean the Go test cache
                    sh "GOBIN=${TEMP_GOBIN} go clean -testcache"

                    script {
                        extraArgs = ""
                        if (env.GERRIT_PROJECT == "cbmultimanager") {
                            extraArgs = "-tags noui"
                        }
                        // Run the unit testing
                        sh "2>&1 GOBIN=${TEMP_GOBIN} go test -v -timeout=15m -count=1 ${extraArgs} -coverprofile=coverage.out ./... | tee ${WORKSPACE}/reports/test.raw"
                    }

                    // Convert the test output into valid 'junit' xml
                    sh "cat ${WORKSPACE}/reports/test.raw | go-junit-report > ${WORKSPACE}/reports/test.xml"

                    // Convert the coverage report into valid 'cobertura' xml
                    sh "GOBIN=${TEMP_GOBIN} gocov convert coverage.out | gocov-xml > ${WORKSPACE}/reports/coverage.xml"
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
                        sh "repo init -u https://github.com/couchbase/manifest -m ${CB_SERVER_MANIFEST} -g all"
                        sh "repo sync -j${PARALLELISM}"
                    }

                    // Fetch the commit we are testing
                    dir("${CB_SERVER_SOURCE_PROJECT}") {
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
            // Post the test results
            junit allowEmptyResults: true, testResults: "reports/test.xml"

            // Post the test coverage
            cobertura autoUpdateStability: false, autoUpdateHealth: false, onlyStable: false, coberturaReportFile: "reports/coverage.xml", conditionalCoverageTargets: "70, 10, 30", failNoReports: false, failUnhealthy: true, failUnstable: true, lineCoverageTargets: "70, 10, 30", methodCoverageTargets: "70, 10, 30", maxNumberOfBuilds: 0, sourceEncoding: "ASCII", zoomCoverageChart: false

            script {
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
            // We don't need the build cache interfering with any subsequent builds
            sh "go clean --cache --testcache"

            // Remove the workspace
            deleteDir()
        }
    }
}

/**
 * Taken from:
 * https://github.com/couchbase/server-cv/blob/a345959cfa6546819b4c03520d0d016bc16c4370/jenkins-jobs/Jenkinsfile
 *
 * Report vote to Gerrit verify-status plugin.
 *
 * Adds the job result to the verify-status sidebar in the Gerrit patch
 * view. This is hacky - the Jenkins plugin that does this for normal jobs
 * does not appear to have been updated to be used from Pipelines.
 * If a way of using the proper plugin is found, or it is updated, it should
 * definitely replace this.
 */
def submitGerritVerifyStatus(value) {
    if (env.GERRIT_PATCHSET_REVISION == null) {
        return
    }
    // Directly report the verify status to Gerrit
    // The jenkins plugin which reports to the verify-status sidebar does not seem to be up to date
    // TODO: Investigate the HTTP API for greater portability (e.g., windows!)
    def url = "http://cv.jenkins.couchbase.com/job/${getJobName()}/job/${env.BRANCH_NAME}/${BUILD_NUMBER}/"

    sh """ssh -p ${env.GERRIT_PORT} buildbot@${env.GERRIT_HOST} \
    -o "UserKnownHostsFile=/dev/null" -o "StrictHostKeyChecking=no" \
    verify-status save --verification \
    "'name=backup-cv-multi-branch-pipeline|value=${value}|url=${url}|reporter=buildbot'" \
    ${GERRIT_PATCHSET_REVISION}"""
}

def getJobType() {
    // e.g., tools.linux.some_testing_change/master
    // we want linux
    return getJobName().tokenize(".")[1]
}

def getJobName() {
    // e.g., tools.linux.some_testing_change/master
    // we want tools.linux.some_testing_change
    return env.JOB_NAME.tokenize("/")[0]
}

def getProjectName() {
    // e.g., tools.linux.some_testing_change/master
    // we want tools
    return getJobName().tokenize(".")[0]
}

def getNodeLabel() {
    def osLabel = ""
    switch(getJobType()) {
        case "windows":
            osLabel = WINDOWS_NODE_LABEL
            break;
        case "macos":
            osLabel = MACOS_NODE_LABEL
            break;
        case ~/aarch64-linux.*/:
            osLabel = "aarch64 && amzn2"
            break;
        default:
            osLabel = LINUX_NODE_LABEL
            break;
    }
    return "${osLabel} && ${env.BRANCH_NAME}"
}

def getGoDowloadURL() {
    def url = "https://golang.org/dl/go${GO_VERSION}"
    def goPlatform = ""
    switch(getJobType()) {
        case "windows":
            osLabel = "windows-amd64.zip"
            break;
        case "macos":
            goPlatform = "darwin-amd64.tar.gz"
            break;
        case ~/aarch64-linux.*/:
            goPlatform = "linux-arm64.tar.gz"
            break;
        default:
            goPlatform = "linux-amd64.tar.gz"
            break;
    }
    return "${url}.${goPlatform}"
}
