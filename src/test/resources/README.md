# Test resources

These resources are downloaded from remote servers and maybe slightly tweaked to make them usable for testing.

## Jira

```
http https://issues.apache.org/jira/rest/api/2/project/SLING/versions | jq '.[0:199]' > src/test/resources/jira/versions.json
http https://issues.apache.org/jira/rest/api/2/version/12329667/relatedIssueCounts | jq '.' > src/test/resources/jira/relatedIssueCounts/12329667.json
http https://issues.apache.org/jira/rest/api/2/version/12329844/relatedIssueCounts | jq '.' > src/test/resources/jira/relatedIssueCounts/12329844.json
http https://issues.apache.org/jira/rest/api/2/search 'jql==project = SLING AND resolution = Unresolved AND fixVersion = "Committer CLI 1.0.0"' 'fields==summary' | jq '.' > src/test/resources/jira/search/unresolved-committer-cli-1.0.0.json
```