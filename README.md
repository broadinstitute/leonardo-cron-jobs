[![Build Status](https://github.com/broadinstitute/leonardo-cron-jobs/workflows/Unit%20Tests/badge.svg)](https://github.com/broadinstitute/leonardo-cron-jobs/actions)
[![codecov](https://codecov.io/gh/broadinstitute/leonardo-cron-jobs/branch/master/graph/badge.svg)](https://codecov.io/gh/broadinstitute/leonardo-cron-jobs)

# Introduction

This repo contains cron jobs running alongside with [Leonardo](https://github.com/databiosphere/leonardo).

# Cron Jobs
[This doc](https://broadworkbench.atlassian.net/wiki/spaces/IA/pages/2699558951/Leonardo+Cron+Jobs) should provide an extensive summary of what these cron jobs do, and also links the original design documents.

## resource-validator

This job detects and fixes anomalies by updating Google resources to match their status in Leonardo database. Apart from

## zombie-monitor
This job detects and fixes anomalies by updating Leonardo database status of resources to match their status in Google or Azure.

## janitor
This job removes prod resources when they are deemed not utilized.

## nuker
This job cleans up cloud resources created by Leonardo in dev/qa projects.

# Running Locally

## Setting environment variables

export LEONARDO_DB_USER=???

export LEONARDO_DB_PASSWORD=???

export LEONARDO_PATH_TO_CREDENTIAL=???

Note: You can get this info from `leonardo/config` directory. To setup `leonardo/config` directory, follow [this](https://github.com/broadinstitute/firecloud-develop#quick-start---how-do-i-set-up-my-configs)

## Run cloud-sql container (if you're connecting to dev leonardo DB instance)
```
docker run \
  -v <your local path to leonardo repo>/leonardo/config/sqlproxy-service-account.json:/config \
  -p 127.0.0.1:3306:3306 \
  gcr.io/cloudsql-docker/gce-proxy:1.16 /cloud_sql_proxy \
  -instances=<mysql instance you'd like to connect> -credential_file=/config
```

## Set up configuration files
Copy `application.conf.example` under each project in dir `[project]/src/main/resources` as `application.conf`. Replace values appropriately.
i.e. the `leonardo-pubsub.google-project`  will need to be one that the user you are configured as will have access to.
Copy `reference.conf.example` under each project in dir `[project]/src/main/resources` as `reference.conf`. Replace values appropriately.

Do not commit the `application.conf` or `reference.conf` as they are used to run the jobs locally only.

## Run a job
```
sbt <project>/run --help
```

e.g. `sbt "resourceValidator/run --dryRun --all"`

## Run unit tests
* For the unit tests that **don't** require access to Leonardo DB:
  * `sbt "testOnly -- -l cronJobs.dbTest"`

* For the unit tests that **do** require access to Leonardo DB:
  * Start Leonardo mysql container locally.
  * Run a Leonardo unit test that results in initializing a Leonardo DB (e.g. [ClusterComponentSpec](https://github.com/DataBiosphere/leonardo/blob/develop/http/src/test/scala/org/broadinstitute/dsde/workbench/leonardo/db/ClusterComponentSpec.scala)).
  * Run each unit test individually as running them concurrently causes some of them to fail.

## Contributing

1. **Run tests manually.** Ideally, we should be able to just run `sbt test`. But for some reason, DB unit tests will fail if they're run this way. So you'd have to run them separately.

Run DB tests by projects will have fewer failures. Here's how you can run them by each project:

- `sbt zombieMonitor/test`

- `sbt resourceValidator/test`

- `sbt janitor/test`

  There will be a few failures, but should succeed when run individually. (Currently `com.broadinstitute.dsp.janitor.DbQueryBuilderSpec` has real error that we need to fix at some point).

These are not run in CI, so you have to make sure you run them manually before merging any PRs. Instructions on running these can be found in the respective `DbReaderSpec` files.

2. **Merge your `leonardo-cron-jobs` PR.** Once your PR is approved you can merge it and a new PR will be automatically created in [terra-helm](https://github.com/broadinstitute/terra-helm).

3. **Merge the automatically generated `terra-helm` PR.** You can merge this yourself. Another automatic commit will bump `leonardo`'s chart version. This will trigger another automatic commit
in [terra-helmfile](https://github.com/broadinstitute/terra-helmfile). Note that this commit will only auto-bump `dev`, and will be auto-merged.
