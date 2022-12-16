#!/usr/bin/env groovy

/**
 * Copyright (C) Couchbase, Inc 2022 - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential.
 *
 * When updating this Jenkinsfile, changes will not take effect immediately; they will
 * take effect once the Jenkins multi-branch pipeline picks up the commit. This therefore
 * means that changes made to the Jenkinsfile in a Gerrit review will not have any effect
 * until they are submitted.
 */

@Library('cvutils')

import hudson.model.Result
import hudson.model.Run
import hudson.Util
import jenkins.model.CauseOfInterruption.UserInterruption

PARALLELISM = 16
CB_SERVER_MANIFEST = "branch-master.xml"
CB_SERVER_MANIFEST_GROUPS = "backup"

pipeline {
    environment {
        SOURCE_DIR = "${WORKSPACE}/source"
        CBBS = "${SOURCE_DIR}/cbbs"

        GOVERSION = "1.19"
        GOROOT = "${WORKSPACE}/go"
        GOBIN = "${GOROOT}/bin"

        PROTOCVERSION= "3.19.4"
        GOLANGCI_LINT_VERSION = "v1.49.0"

        PATH="${PATH}:${GOBIN}:${WORKSPACE}/bin:${WORKSPACE}/protoinstall/bin:${WORKSPACE}/node-v12.18.2-linux-x64/bin"
    }

    agent { label cvutils.getNodeLabel() }

    stages {
        stage("Setup") {
            steps {
                script {
                    cvutils.configGerritTrigger()
                    cvutils.checkRequiredEnvars()
                }

                timeout(time: 10, unit: "MINUTES") {
                    dir("${SOURCE_DIR}") {
                        script {
                            cvutils.repo(CB_SERVER_MANIFEST, CB_SERVER_MANIFEST_GROUPS, env.PARALLELISM)
                        }
                    }

                    // Fetch the commit we are testing
                    dir("${CBBS}") {
                        script {
                            cvutils.checkOutPatch()
                        }
                    }

                    dir("${WORKSPACE}") {
                        script {
                            cvutils.installGo(GOVERSION)
                            cvutils.installGoDeps(GOBIN, GOLANGCI_LINT_VERSION)
                            cvutils.installNode()
                        }
                    }
                }
            }
        }

        stage("Get Dependencies") {
            steps {
                timeout(time: 10, unit: "MINUTES") {
                    // get protoc compiler
                    dir ("${WORKSPACE}") {
                        script {
                            cvutils.installProto(PROTOCVERSION, CBBS)
                        }
                    }

                    dir("${CBBS}/ui/backup-ui") {
                        // Install dev dependencies used for linting
                        sh "npm i --saveDev"
                    }
                }
            }
        }

        stage("Make proto definitions") {
            steps {
                timeout(time: 10, unit: "MINUTES") {
                    // This has to be done before linting as if not linting will fail as it won't be able to find
                    // the definitions for the various Go structures.
                    dir("${CBBS}") {
                        script {
                            cvutils.buildTargets(PARALLELISM, "protobuf")
                        }
                    }
                }
            }
        }

        stage("Lint Go Code") {
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    dir("${CBBS}") {
                        script {
                            cvutils.runLint()
                        }
                    }
                }
            }
        }

        stage("Lint UI") {
            steps {
                timeout(time: 10, unit:"MINUTES") {
                    dir("${CBBS}/ui/backup-ui") {
                        sh "node_modules/eslint/bin/eslint.js --ext .html,.js -c .eslintrc.yml ui-current/"
                    }
                }
            }
        }

        stage("Build") {
            steps {
                timeout(time: 10, unit: "MINUTES") {
                    // Build service
                    dir("${CBBS}") {
                        script {
                            cvutils.buildTargets(PARALLELISM, "")
                        }
                    }
                }
            }
        }

        stage("Test") {
            steps {
                script {
                    cvutils.runGoTests(GOBIN, CBBS, "")
                }
            }
        }
    }

    post {
        always {
            script {
                cvutils.postTestResults()
                cvutils.postCoverage()
            }
        }

        success {
            build(job: "/cbbs-cv-integration-tests/master", parameters: [
                string(name: "GERRIT_REFSPEC", value: "${GERRIT_REFSPEC}"),
                string(name: "GERRIT_CHANGE_URL", value: "${GERRIT_CHANGE_URL}"),
                string(name: "GERRIT_CHANGE_SUBJECT", value: "${GERRIT_CHANGE_SUBJECT}"),
                string(name: "GERRIT_CHANGE_OWNER_NAME", value: "${GERRIT_CHANGE_OWNER_NAME}"),
                string(name: "GERRIT_PATCHSET_NUMBER", value: "${GERRIT_PATCHSET_NUMBER}"),
                string(name: "GERRIT_CHANGE_ID", value: "${GERRIT_CHANGE_ID}"),
                string(name: "GERRIT_BRANCH", value: "${GERRIT_BRANCH}"),
                string(name: "GERRIT_PROJECT", value: "${GERRIT_PROJECT}"),
                string(name: "GERRIT_CHANGE_NUMBER", value: "${GERRIT_CHANGE_NUMBER}"),
            ], propagate: false, wait: false)
        }

        cleanup {
            script {
                cvutils.cleanUp()
            }
        }
    }
}