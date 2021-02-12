package datadog.trace.bootstrap.instrumentation.ci;

import datadog.trace.bootstrap.instrumentation.ci.git.CommitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.PersonInfo;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

class JenkinsInfo extends CIProviderInfo {

  // https://wiki.jenkins.io/display/JENKINS/Building+a+software+project
  public static final String JENKINS = "JENKINS_URL";
  public static final String JENKINS_PROVIDER_NAME = "jenkins";
  public static final String JENKINS_PIPELINE_ID = "BUILD_TAG";
  public static final String JENKINS_PIPELINE_NUMBER = "BUILD_NUMBER";
  public static final String JENKINS_PIPELINE_URL = "BUILD_URL";
  public static final String JENKINS_PIPELINE_NAME = "JOB_NAME";
  public static final String JENKINS_JOB_URL = "JOB_URL";
  public static final String JENKINS_WORKSPACE_PATH = "WORKSPACE";
  public static final String JENKINS_GIT_REPOSITORY_URL = "GIT_URL";
  public static final String JENKINS_GIT_COMMIT = "GIT_COMMIT";
  public static final String JENKINS_GIT_BRANCH = "GIT_BRANCH";

  JenkinsInfo() {
    final String gitBranch = buildGitBranch();

    final String workspace = expandTilde(System.getenv(JENKINS_WORKSPACE_PATH));
    final GitInfo gitInfo =
        this.gitInfoExtractor.headCommit(Paths.get(workspace, ".git").toFile().getAbsolutePath());
    final CommitInfo commitInfo = gitInfo.getCommit();
    final PersonInfo author = commitInfo.getAuthor();
    final PersonInfo committer = commitInfo.getCommitter();

    this.ciTags =
        new CITagsBuilder()
            .withCiProviderName(JENKINS_PROVIDER_NAME)
            .withCiPipelineId(System.getenv(JENKINS_PIPELINE_ID))
            .withCiPipelineName(buildCiPipelineName(gitBranch))
            .withCiPipelineNumber(System.getenv(JENKINS_PIPELINE_NUMBER))
            .withCiPipelineUrl(System.getenv(JENKINS_PIPELINE_URL))
            .withCiWorkspacePath(workspace)
            .withGitRepositoryUrl(filterSensitiveInfo(System.getenv(JENKINS_GIT_REPOSITORY_URL)))
            .withGitCommit(System.getenv(JENKINS_GIT_COMMIT))
            .withGitBranch(gitBranch)
            .withGitTag(buildGitTag())
            .withGitCommitAuthorName(author.getName())
            .withGitCommitAuthorEmail(author.getEmail())
            .withGitCommitAuthorDate(author.getISO8601Date())
            .withGitCommitCommitterName(committer.getName())
            .withGitCommitCommitterEmail(committer.getEmail())
            .withGitCommitCommitterDate(committer.getISO8601Date())
            .withGitCommitMessage(commitInfo.getFullMessage())
            .build();
  }

  private String buildGitBranch() {
    final String gitBranchOrTag = System.getenv(JENKINS_GIT_BRANCH);
    if (gitBranchOrTag != null && !gitBranchOrTag.contains("tags")) {
      return normalizeRef(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitTag() {
    final String gitBranchOrTag = System.getenv(JENKINS_GIT_BRANCH);
    if (gitBranchOrTag != null && gitBranchOrTag.contains("tags")) {
      return normalizeRef(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildCiPipelineName(final String branch) {
    final String jobName = System.getenv(JENKINS_PIPELINE_NAME);
    return filterJenkinsJobName(jobName, branch);
  }

  private String filterJenkinsJobName(final String jobName, final String gitBranch) {
    if (jobName == null) {
      return null;
    }

    // First, the git branch is removed from the raw jobName
    final String jobNameNoBranch;
    if (gitBranch != null) {
      jobNameNoBranch = jobName.trim().replace("/" + gitBranch, "");
    } else {
      jobNameNoBranch = jobName;
    }

    // Once the branch has been removed, we try to extract
    // the configurations from the job name.
    // The configurations have the form like "key1=value1,key2=value2"
    final Map<String, String> configurations = new HashMap<>();
    final String[] jobNameParts = jobNameNoBranch.split("/");
    if (jobNameParts.length > 1 && jobNameParts[1].contains("=")) {
      final String configsStr = jobNameParts[1].toLowerCase().trim();
      final String[] configsKeyValue = configsStr.split(",");
      for (final String configKeyValue : configsKeyValue) {
        final String[] keyValue = configKeyValue.trim().split("=");
        configurations.put(keyValue[0], keyValue[1]);
      }
    }

    if (configurations.isEmpty()) {
      // If there is no configurations,
      // the jobName is the original one without branch.
      return jobNameNoBranch;
    } else {
      // If there are configurations,
      // the jobName is the first part of the splited raw jobName.
      return jobNameParts[0];
    }
  }
}
