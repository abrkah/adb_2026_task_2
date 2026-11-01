# adb_2026_task_2
Task 2 of Winter Semester 2026/27 advanced database proseminar.

Concurrent `Blocker` transactions on PostgreSQL: deadlocks, client-side restart, and starvation avoidance.

## Files
| File | Purpose |
|---|---|
| `src/Blocking.java` | Original program: produces row-level deadlocks, aborted tasks are lost |
| `src/RestartBlocking.java` | Aborted transactions (SQLSTATE `40P01`) are rolled back and restarted until they commit |
| `src/NoLiveLocks.java` | Deadlock and starvation avoidance: ordered row locking + restart + escalation to exclusive execution |
| `src/ConnectionFactory.java` | Local connection settings (database `deadlock_demo`, user `ab`) - adjust to your setup |
| `demo-output/` | Console logs of the runs used in the slides; `screenshots/` has console images rendered from these logs |

## Setup
1. PostgreSQL (tested with 14), create role and database:
   `CREATE ROLE ab LOGIN PASSWORD 'ab'; CREATE DATABASE deadlock_demo OWNER ab;`
2. JDBC driver 42.2.18 into `lib/` (`lib/*.jar` is git-ignored):
   https://jdbc.postgresql.org/download.html
3. Compile and run:
   ```
   javac -d out src/*.java
   java -cp out:lib/postgresql-42.2.18.jar RestartBlocking [tasks] [queriesPerTask] [threads]
   java -cp out:lib/postgresql-42.2.18.jar NoLiveLocks     [tasks] [queriesPerTask] [threads]
   ```
   Defaults: 30 tasks, 3 queries per task, 4 threads. Isolation level is READ COMMITTED.

## NoLiveLocks concept
1. **Ordered locking** - `SELECT ... ORDER BY data_id FOR UPDATE` locks all rows a task needs in one global order, so lock cycles rarely form.
2. **Restart** - the remaining deadlocks (predicates are re-evaluated under READ COMMITTED) are rolled back and retried with randomized backoff.
3. **Escalation** - after 5 aborts (`-DmaxOptimistic=n`) a task takes a PostgreSQL advisory lock exclusively and runs alone; every other transaction holds it shared. Without concurrent transactions it cannot be a deadlock victim, so it is guaranteed to commit. PostgreSQL queues lock requests, so the waiting task is not overtaken forever. The gate lives in the database, so it also works across several JVMs (all clients must use it; `-DskipSetup=true` joins an existing table).

## Results (one laptop, PostgreSQL 14, single runs)
| Setup | Original | RestartBlocking | NoLiveLocks |
|---|---|---|---|
| 30 tasks, 3 queries, 4 threads | 9.1 s, 21 of 30 committed | 6.3 s, 30 of 30 | 4.8 s, 30 of 30 |
| 200 tasks, 3 queries, 16 threads | - | 318.6 s (1 run), 880 deadlocks, worst task 48 attempts | 26.3 s (mean of 18.4, 23.6, 36.9 s), 8 to 66 deadlocks, worst task 6 attempts |

### Parameter sweep (tasks / queries per task / threads; all runs committed every task)
| Setup | RestartBlocking | NoLiveLocks |
|---|---|---|
| 10 / 1 / 1 | 0.5 s | 0.6 s |
| 30 / 0 / 4 | 0.1 s | 0.1 s |
| 30 / 1 / 4 | 1.1 s | 1.8 s (1 deadlock) |
| 30 / 3 / 1 | 2.0 s | 2.4 s |
| 30 / 10 / 4 | 45.3 s | 9.6 s (5 deadlocks) |
| 50 / 3 / 8 | 27.8 s | 10.5 s (14 deadlocks) |

With little contention (one thread or one query) the extra locking `SELECT` makes `NoLiveLocks` slightly slower; with contention it wins clearly.

### Repeats (mean of 3 runs)
| Setup | RestartBlocking | NoLiveLocks |
|---|---|---|
| 30 / 3 / 4 | 8.4 s (7.7, 8.5, 9.0) | 6.7 s (4.5, 4.9, 10.7) |
| 50 / 3 / 8 | 27.4 s (22.2, 17.2, 42.8) | 8.5 s (13.6, 2.3, 9.8) |

Runtimes vary a lot between runs; the ranking at higher contention was stable.

### Escalation and multi-process tests
With `-DmaxOptimistic=1` (forces escalations) 100 tasks / 16 threads finished 100 of 100 with 4 escalations. Two JVMs on the same table (100 tasks, 8 threads each) both finished 100 of 100 (8 and 6 escalations, no errors).
