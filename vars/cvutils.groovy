//package com.couchbase

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

def configGerritTrigger() {
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
                    branches: [
                        [
                            compareType: "PLAIN",
                            // disable pipeline for now
                            // pattern: env.BRANCH_NAME 
                            pattern: "disabled"
                        ]
                    ]
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

def getNodeLabel() {
    def osLabel = ""
    switch(getJobType()) {
        case "windows":
            osLabel = "msvc2017"
            break;
        case "macos":
            osLabel = "kv-macos"
            break;
        case ~/aarch64-linux.*/:
            osLabel = "aarch64 && amzn2"
            break;
        default:
            osLabel = "ubuntu-18.04 && large"
            break;
    }
    return "${osLabel} && ${env.BRANCH_NAME}"
}

def getGoDowloadURL(version) {
    def url = "https://golang.org/dl/go${version}"
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

def repo(manifest, groups, parallelism) {
    // get relevant projects
    sh "repo init -u https://github.com/couchbase/manifest -m ${manifest} -g ${groups}"
    sh "repo sync --jobs=${parallelism}"
}

def checkOutPatch() {
    sh "git fetch ssh://buildbot@review.couchbase.org:29418/${getProjectName()} ${GERRIT_REFSPEC}"
    sh "git checkout FETCH_HEAD"
}

def checkRequiredEnvars() {
  // Ensure we have the relevant gerrit envars
  requiredEnvVars = ['GERRIT_HOST',
    'GERRIT_PORT',
    'GERRIT_PROJECT',
    'GERRIT_PATCHSET_REVISION',
    'GERRIT_REFSPEC',
    'GERRIT_CHANGE_ID'
  ]

  for (var in requiredEnvVars) {
    if (!env.getProperty(var)) {
      error "Required environment variable '${var}' not set."
    }
  }
}

def installNode() {
    // Install node
    sh "curl -sSfL https://nodejs.org/dist/v12.18.2/node-v12.18.2-linux-x64.tar.gz | tar xz"
}

def installGo(version) {
    sh "curl -sSfL ${getGoDowloadURL(version)} | tar xz"
}

def installGoDeps(goBin, goLintVersion) {
  // get golangci-lint binary
  sh "curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/${goLintVersion}/install.sh | sh -s -- -b ${goBin} ${goLintVersion}"
  sh "golangci-lint --version"

  // Unit test reporting
  sh "GOBIN=${goBin} go install github.com/jstemmer/go-junit-report@latest"

  // Coverage reporting
  sh "GOBIN=${goBin} go install github.com/axw/gocov/gocov@latest"
  sh "GOBIN=${goBin} go install github.com/AlekSi/gocov-xml@latest"
}

def runLint() {
    sh "golangci-lint run --timeout 5m"
}

def runGoTests(goBin, source, extraArgs) {
  // Create somewhere to store our coverage/test reports
  sh "mkdir -p reports"

  dir("${source}") {
    // Clean the Go test cache
    sh "GOBIN=${goBin} go clean -testcache"

    // Run the unit testing
    sh "2>&1 GOBIN=${goBin} go test -v -timeout=15m -count=1 ${extraArgs} -coverprofile=coverage.out ./... | tee ${WORKSPACE}/reports/test.raw"

    // Convert the test output into valid 'junit' xml
    sh "cat ${WORKSPACE}/reports/test.raw | go-junit-report > ${WORKSPACE}/reports/test.xml"

    // Convert the coverage report into valid 'cobertura' xml
    sh "GOBIN=${goBin} gocov convert coverage.out | gocov-xml > ${WORKSPACE}/reports/coverage.xml"
  }
}

def installProto(version, sourceDir) {
    // get protoc compiler
    sh "rm -rf protoinstall"
    sh "mkdir protoinstall"
    sh "curl -sSfLOJ https://github.com/protocolbuffers/protobuf/releases/download/v${version}/protoc-${version}-linux-x86_64.zip"
    sh "unzip protoc-${version}-linux-x86_64.zip -d ${WORKSPACE}/protoinstall"

    dir("${sourceDir}") {
        sh "go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest"
        sh "go install google.golang.org/protobuf/cmd/protoc-gen-go@latest"
    }
}

def buildTargets(parallelism, targets) {
    sh "make -j ${parallelism} ${targets}"
}

def cleanUp() {
    // We don't need the build cache interfering with any subsequent builds
    sh "go clean --cache --testcache"

    // Remove the workspace
    deleteDir()
}

def postTestResults() {
    // Post the test results
    junit allowEmptyResults: true, testResults: "reports/test*.xml"
}

def postCoverage() {
    // Post the test coverage
    cobertura autoUpdateStability: false, autoUpdateHealth: false, onlyStable: false, coberturaReportFile: "reports/coverage*.xml", conditionalCoverageTargets: "70, 10, 30", failNoReports: false, failUnhealthy: true, failUnstable: true, lineCoverageTargets: "70, 10, 30", methodCoverageTargets: "70, 10, 30", maxNumberOfBuilds: 0, sourceEncoding: "ASCII", zoomCoverageChart: false
}

return this