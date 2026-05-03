# ThreadPool Regression Latest Result

- Run URL: https://github.com/RockyLOMO/rxlib/actions/runs/25274975813
- Commit: ae43d95d0f0c14478c005f4b7ed97a8deccf7d55
- Branch: master
- Trigger: push
- Focused tests: success
- Pattern tests: success
- Updated at: 2026-05-03T09:07:31Z

## Commands

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```
