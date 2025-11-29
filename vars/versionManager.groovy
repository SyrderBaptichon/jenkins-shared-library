#!/usr/bin/env groovy

/**
 * Version Manager for Jenkins Pipelines
 * Based on version.properties file with major.minor.patch structure
 */

/**
 * Check if build should be skipped based on commit message
 * @return true if build should be skipped
 */
def shouldSkipBuild() {
    def commitMessage = sh(
        script: 'git log -1 --pretty=%B',
        returnStdout: true
    ).trim()
    
    echo "Last commit message: ${commitMessage}"
    
    return commitMessage.contains('[ci skip]') || 
           commitMessage.contains('[skip ci]') ||
           commitMessage.contains('Jenkins: Version bump')
}

/**
 * Read version from version.properties file
 * @param file Path to version.properties (default: 'version.properties')
 * @return Map containing version information
 */
def readVersion(String file = 'version.properties') {
    try {
        def versionProps = readProperties file: file
        def major = versionProps['version.major']
        def minor = versionProps['version.minor']
        def patch = versionProps['version.patch']
        
        return [
            major: major,
            minor: minor,
            patch: patch,
            semver: "${major}.${minor}.${patch}"
        ]
    } catch (Exception e) {
        error "✗ Failed to read version from ${file}: ${e.message}"
    }
}

/**
 * Generate full version string with build number and commit hash
 * Format: major.minor.patch-buildNumber-shortCommit
 * @param buildNumber Jenkins build number
 * @param commitHash Git commit hash
 * @return Formatted version string (e.g., 1.0.0-123-abc1234)
 */
def generateVersion(def buildNumber, String commitHash = null) {
    def version = readVersion()
    def shortCommit = commitHash ? commitHash.take(7) : env.GIT_COMMIT?.take(7) ?: 'unknown'
    
    return "${version.semver}-${buildNumber}-${shortCommit}"
}

/**
 * Set Maven project version
 * @param version Version string to set
 */
def setMavenVersion(String version) {
    echo "Setting Maven project version to: ${version}"
    try {
        sh """
            mvn versions:set -DnewVersion=${version} -DgenerateBackupPoms=false
        """
    } catch (Exception e) {
        error "✗ Failed to set Maven version: ${e.message}"
    }
}

/**
 * Increment patch version and commit to Git
 * @param config Configuration map with:
 *   - gitCredentialsId: Jenkins credentials ID (default: 'git-credentials')
 *   - userName: Git user name (default: 'Jenkins CI')
 *   - userEmail: Git user email (default: 'jenkins@example.com')
 *   - versionFile: Path to version file (default: 'version.properties')
 * @return Map with success status and new version
 */
def incrementAndCommit(Map config = [:]) {
    def gitCredentialsId = config.gitCredentialsId ?: 'git-credentials'
    def userName = config.userName ?: 'Jenkins CI'
    def userEmail = config.userEmail ?: 'jenkins@example.com'
    def versionFile = config.versionFile ?: 'version.properties'
    
    try {
        // Read current version
        def versionProps = readProperties file: versionFile
        def currentPatch = versionProps['version.patch'].toInteger()
        def newPatch = currentPatch + 1
        
        echo "Incrementing version: ${versionProps['version.major']}.${versionProps['version.minor']}.${currentPatch} -> ${versionProps['version.major']}.${versionProps['version.minor']}.${newPatch}"
        
        // Write updated version file
        writeFile file: versionFile, text: """version.major=${versionProps['version.major']}
version.minor=${versionProps['version.minor']}
version.patch=${newPatch}
"""
        
        // Configure Git
        sh """
            git config user.name "${userName}"
            git config user.email "${userEmail}"
        """
        
        // Check if there are changes
        def gitStatus = sh(
            script: "git status --porcelain ${versionFile}",
            returnStdout: true
        ).trim()
        
        if (gitStatus) {
            // Commit and push
            withCredentials([usernamePassword(
                credentialsId: gitCredentialsId,
                usernameVariable: 'GIT_USERNAME',
                passwordVariable: 'GIT_PASSWORD'
            )]) {
                // Get branch name and clean it
                def branchName = env.GIT_BRANCH
                if (branchName.startsWith('origin/')) {
                    branchName = branchName.replace('origin/', '')
                }
                
                // Get Git URL and add credentials
                def gitUrl = sh(
                    script: 'git config --get remote.origin.url',
                    returnStdout: true
                ).trim()
                
                def authenticatedUrl = gitUrl
                if (gitUrl.startsWith('https://')) {
                    authenticatedUrl = gitUrl.replace('https://', "https://${GIT_USERNAME}:${GIT_PASSWORD}@")
                } else if (gitUrl.startsWith('http://')) {
                    authenticatedUrl = gitUrl.replace('http://', "http://${GIT_USERNAME}:${GIT_PASSWORD}@")
                }
                
                sh """
                    git add ${versionFile}
                    git commit -m "Jenkins: Version bump to ${versionProps['version.major']}.${versionProps['version.minor']}.${newPatch} [ci skip]"
                    git push ${authenticatedUrl} HEAD:${branchName}
                """
            }
            
            echo "✓ Version update committed and pushed"
            return [
                success: true,
                newVersion: "${versionProps['version.major']}.${versionProps['version.minor']}.${newPatch}"
            ]
        } else {
            echo "ℹ️ No version changes to commit"
            return [success: false, reason: 'No changes']
        }
    } catch (Exception e) {
        echo "⚠ Failed to commit version update: ${e.message}"
        return [success: false, error: e.message]
    }
}

/**
 * Complete version setup for pipeline
 * Checks for [ci skip] and generates version string
 * @param config Configuration map (optional)
 * @return Version string
 */
def setup(Map config = [:]) {
    try {
        // Check if build should be skipped
        if (shouldSkipBuild()) {
            echo "⏭️ Skipping build - triggered by version bump commit"
            currentBuild.result = 'NOT_BUILT'
            currentBuild.description = 'Skipped: CI version bump commit'
            error('Build skipped - version bump commit detected')
        }
        
        // Generate version
        def version = generateVersion(env.BUILD_NUMBER, env.GIT_COMMIT)
        
        echo "✓ Version setup complete"
        echo "Version: ${version}"
        echo "Branch: ${env.GIT_BRANCH}"
        echo "Commit: ${env.GIT_COMMIT}"
        
        return version
    } catch (Exception e) {
        if (currentBuild.result == 'NOT_BUILT') {
            throw e
        }
        error "✗ Version setup failed: ${e.message}"
    }
}

return this
