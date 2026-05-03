# ThreadPool Regression Latest Result

- Run URL: https://github.com/RockyLOMO/rxlib/actions/runs/25273077455
- Commit: 8b28d01bd0582bbc3ee09a6514fd4010e87d4f80
- Branch: master
- Trigger: push
- Focused tests: success
- Pattern tests: success
- Updated at: 2026-05-03T07:30:45Z

## Commands

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```
