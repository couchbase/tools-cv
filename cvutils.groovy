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
    def url = "http://cv.jenkins.couchbase.com/job/backup-cv-multi-branch-pipeline/job/${env.BRANCH_NAME}/${BUILD_NUMBER}/"

    sh """ssh -p ${env.GERRIT_PORT} buildbot@${env.GERRIT_HOST} \
    -o "UserKnownHostsFile=/dev/null" -o "StrictHostKeyChecking=no" \
    verify-status save --verification \
    "'name=backup-cv-multi-branch-pipeline|value=${value}|url=${url}|reporter=buildbot'" \
    ${GERRIT_PATCHSET_REVISION}"""
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