# ThreadPool Regression Latest Result

- Run URL: https://github.com/RockyLOMO/rxlib/actions/runs/25277860974
- Commit: 20bac4b8762031c0a4fd33a0f6f25e8adfca0080
- Branch: ai/thread-pool-todo-impl
- Trigger: workflow_dispatch
- Focused tests: success
- Pattern tests: success
- Updated at: 2026-05-03T11:30:07Z

## Commands

```bash
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true -Dtest=ThreadPoolWheelTimerRegressionTest,ThreadPoolQueueOfferModeTest,RxConfigTest,TasksCompatibilityTest,ThreadPoolTest test --batch-mode --no-transfer-progress
mvn -pl rxlib -am -Dmaven.test.skip=false -DskipTests=false -Dgpg.skip=true -Dmaven.gpg.skip=true '-Dtest=*CpuWatchman*,*WheelTimer*,*Tasks*' test --batch-mode --no-transfer-progress
```
