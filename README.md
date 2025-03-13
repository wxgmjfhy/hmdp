# 黑马点评

## 短信登录

最初的方案是: 
- 用户请求验证码, 后台 tomcat 服务器会创建一个 session 存储在服务器中 **(之前一直以为 session 是浏览器创建并存储的)**, 并将验证码存储到 session 中 (当然验证码也要发送给用户), 并且将 session id 发给用户端 **(在浏览器响应头中设置 JSESSIONID)**
- 然后用户请求登录, 会通过 session id 去找到之前创建的 session, 接着将用户输入的验证码与 session 中存储的验证码比较以判断能否登录
- 用户成功登录后, 后台会将用户信息也存到 session 中

这样, 检查用户请求带有的 session id 对应的 session 中有无用户信息, 就可以检查用户是否已经登录

这种方案的弊端在于: 实际开发中, 一般使用的是 tomcat 服务器集群, 用户不同的请求可能访问不同的 tomcat 服务器, **如果服务器存储的 session 不共享, 那么用户请求带有的 session id 就找不到用户信息, 登录校验就有问题** (如果共享 session, 则会造成额外的开销)

***

因此这里使用 redis, 相较 tomcat 服务器集群的数据是默认不共享的 (可以通过技术手段或配置实现共享, 但是开销大, 数据同步有延迟等等), 一个 redis 实例或者一个 redis 集群是数据共享的, 并且 redis 具有好的扩展性, 以及高性能, 这与存储 session 的需求是符合的: **数据量较小, 频繁的读写, 临时性, 数据共享性**

使用 redis 的方案是:
- 用户请求验证码, 后台将验证码存储在 redis 中, **key 的名称与用户的手机号关联**, 并设置 key 的有效期为 2 分钟
- 然后用户请求登录, 会通过用户的手机号从 redis 获取验证码与用户输入的验证码进行校验
- 用户成功登录后, 后台会将用户信息存到 redis 中, **key 的名称与一个随机生成的唯一 token 关联**, 并设置 key 的有效期为 30 分钟; 再将 token 返回给前端, 设置到请求头中

这样, 根据请求头中 token 就可以从 redis 中读取用户信息

然后这里实现了两个拦截器:

- 前一个拦截器拦截所有请求, 如果 redis 中有 token 对应的 key 存储着用户信息, 则将用户信息存储到 ThreadLocal 中
- 后一个拦截器则检查 ThreadLocal 中有无用户信息, 即可进行登录校验

在前一个拦截器中, 如果 redis 中有 token 对应 key 存储着用户信息, 还要刷新对应的用户信息在 redis 中的有效期, 这样就实现了**用户操作时维持登录状态**的效果

## 商户查询缓存

缓存就是**把数据库中的数据取出来到内存上, 以减少数据库压力**: 因为数据库建立在磁盘上, 相较内存, 有更低的成本, 更稳定的存储性能等等, 但不如内存能支持大量频繁的读写操作; 如果查询时, **先查询缓存并且数据在缓存中存在**, 就可以避免大量的查询直接访问到数据库, 导致数据库性能降低甚至崩溃

缓存有多层, 比如浏览器缓存, 应用层缓存 (包括 tomcat 本地缓存, redis 等)

这里我们就使用 redis 实现缓存: **如果查询的数据在 redis 中不存在, 就从数据库中查询数据存储到 redis 中, 根据需求设置过期时间**

那么如何使 redis 中数据和数据库中一致呢? 在数据库数据更新时, 我们也要更新缓存, 不过这里采用的则是**删除缓存**: 如果每次数据库更新都要更新缓存, 而多次更新期间没有人查询, 这样就不如等有人来查询时, 再更新缓存, 所以在数据库更新时, 我们删除缓存即可, 等到有人查询时, 再从数据库中获取数据缓存, 达成更新的效果

还要考虑数据库更新和删除缓存这两个操作的顺序: 
- 如果我们先删除缓存, 就可能出现`线程 1 删除缓存后还没更新数据库时, 线程 2 没查到缓存, 从数据库查询了旧的数据并缓存`的情况
- 如果我们先更新数据库, 虽然也可能出现`线程 1 没查到缓存, 从数据库查询到了数据, 还没写入缓存时, 线程 2 更新了数据库, 导致线程 1 写入缓存的是旧数据`的情况, 但因为**缓存的写入通常要远远快于数据库的写入**, 所以这种情况出现的概率很低

因此为了保证数据一致性, 选择: **先更新数据库, 再删除缓存**

然后这里有三个问题: 缓存穿透, 缓存击穿, 缓存雪崩

### 缓存穿透

缓存穿透: 缓存和数据库中都没有查询的数据, 那么有可能大量恶意查询仍然直接访问到数据库, 缓存没起作用

这里我们可以采取**空值**来处理: 如果数据库没有查询到数据, 就在缓存中存一个空值, 表示没有这个数据, 这样的弊端是增加了内存, 也可能造成短期的数据不一致 (为空值设置的过期时间一般很短)

或者可以采取**布尔过滤器**, 即对要查询的数据进行判断, 判断数据库有没有这个数据, 其利用的是哈希思想, 可能因哈希冲突产生误判

此外还有一些辅助策略:
* 增强查询 id 的复杂度, 避免被猜测 id 规律
* 做好数据的基础格式校验
* 加强用户权限校验
* 做好热点参数的限流

### 缓存击穿

缓存击穿: 要查询的数据在缓存中消失时, 高并发的访问导致缓存重建完成之前, 仍有大量的请求直接访问了数据库

因此, 我们**只能让一个线程去访问数据库**, 可以想到使用**互斥锁**: 可以利用 redis 的 SETNX (set if not exists): 我们设置一个 key, 表示某个数据对应的锁, 当需要缓存重建时, 所有线程都尝试去 SETNX 这个 key, 等于是尝试获取锁, **只有获取到锁的线程去访问数据库**, 并执行与**缓存穿透**一样的逻辑, 最后释放锁; **没有获取到锁的线程则休眠一段时间后, 重新尝试获取缓存**

```java
/*
 * 解决缓存击穿: 互斥锁
 * 解决缓存穿透: 空值
 */
public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
    String key = keyPrefix + id;
    String json = stringRedisTemplate.opsForValue().get(key);

    // 如果缓存数据存在, 直接返回数据
    if (StrUtil.isNotBlank(json)) {
        return JSONUtil.toBean(json, type);
    }

    // 如果是空值, 说明数据不存在, 返回不存在
    if ("".equals(json)) {
        return null;
    }

    // 如果缓存不存在, 需要只让拿到锁的一个线程进行缓存重建, 其余线程重试获取缓存
    String lockKey = LOCK_SHOP_KEY + id;
    R result = null;

    try {
        boolean getLock = tryLock(lockKey);

        // 如果没拿到锁, 重试获取缓存
        if (!getLock) {
            Thread.sleep(50);
            return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
        }

        // 如果拿到锁, 缓存重建
        result = dbFallback.apply(id);

        // 如果数据不存在, 写入空值, 返回不存在
        if (result == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 如果数据存在, 写入数据, 返回数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(result), time, unit);
        return result;
    } catch (InterruptedException e) {
        throw new RuntimeException(e);
    } finally {
        unlock(lockKey);
    }
}

private boolean tryLock(String key) {
    return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "tryLock", LOCK_SHOP_TTL, TimeUnit.SECONDS));
}

private void unlock(String key) {
    stringRedisTemplate.delete(key);
}
```

此外还有一种**逻辑过期**的方案: 在 redis 中, 我们不对 key 设置过期时间, 但是要在 key 的 value 中记录过期时间, **这样保证了数据在缓存中始终存在 (但这样的前提是, 更新数据库时不能删除缓存)**

既然保证了数据在缓存中始终存在, 那么如果缓存中没有, 说明数据库中也没有数据; 如果缓存中有, 则要**检查缓存是否过期**, 如果过期, 则要进行缓存重建, 这里也需要使用**互斥锁**: **唯一一个获取锁的线程开启独立线程, 异步进行缓存重建**, 而所有线程直接返回查询到的数据, 无论新旧 (这样直到异步缓存重建完成之前, 返回的都是旧的数据, 但避免了大量线程阻塞对性能造成的影响)

### 缓存雪崩

缓存雪崩: 同一时段大量的缓存 key 同时失效或者 redis 服务宕机, 导致大量请求到达数据库, 带来巨大压力

解决方案:
* 给不同的 key 的 TTL 添加随机值
* 利用 redis 集群提高服务的可用性
* 给缓存业务添加降级限流策略
* 给业务添加多级缓存

## 优惠券秒杀

秒杀优惠券的特点: 高并发与瞬时流量集中, 限量, 限时, 可能还有限购

最基本的业务逻辑: 新增秒杀优惠券时, 存储一个优惠券到数据库表 tb_voucher, 存储秒杀优惠券的信息到 tb_seckill_voucher, 然后可以根据 tb_seckill_voucher 中的 voucher_id 和 tb_voucher 中的 id 进行关联; 下单秒杀优惠券时, 要判断是否在秒杀时间内, 以及秒杀优惠券的库存是否充足, 都满足则下单, 存储订单到 tb_voucher_order, 并且更新库存

如果是单线程, 最基本的业务逻辑没有问题; 但是在多线程环境下, 就会发生**超卖问题**: 一个线程在查询到库存充足之后和下单之前这期间, 其他线程已经更新了库存, 导致下单时库存实际不足

常见的解决方案是加**锁**, 而锁又有两种:
- 悲观锁: 认为线程安全问题一定发生, 对操作数据相关操作**加锁**使线程串行执行
    - 比较适合写操作频繁的场景
    - 实现相对简单
    - 降低了系统的并发处理能力
    - 可能死锁
- 乐观锁: 认为线程安全问题不一定发生, **不加锁**而是利用一些**判断**来防止线程安全问题的发生
    - 比较适合读操作频繁的场景
    - 实现相对复杂
    - 高并发性能好
    - 避免死锁

常见的悲观锁比如 synchronized, lock, 常见的乐观锁比如比较版本号; 而在超卖问题中, 我们可以使用**乐观锁**, 比如在更新库存时检查此时库存和之前查询的库存是否一致 (等效于比较版本号), 但这样会导致失败率太高: 如果多个线程都查询到了同一个库存, 然后一个线程先更新库存后, 其余线程就都会检查到库存不一致

因此, 最后采用的**乐观锁**方案是: 对数据库操作更新库存时, **检查库存是否大于 0**

***

接着, 添加了一个业务逻辑: **秒杀优惠券只能一人一单**

最基本的业务逻辑: 下单前, 数据库查询一下符合用户 id 和优惠券 id 的订单的数量, 如果不为 0, 就不允许下单

如果是单线程, 最基本的业务逻辑没有问题: 但是在多线程环境下, 就会发生**超卖问题**: 多个线程的请求一样, 都是一个用户对于一个秒杀优惠券的订单, 而在第一个订单成功存储到数据库中前, 多个线程都没查到订单, 都认为自己满足了条件

因此我们还是加锁, 这里是写操作, 采用**悲观锁**: 将查询订单, 乐观锁尝试修改库存, 保存订单这几个操作放在一个方法 createVoucherOrder 里, 将整个方法用 synchronized 锁住

因为可能有修改库存和保存订单两个数据库操作, 所以要对这个方法进行**事务管理**, 而 Spring 事务管理基于 AOP 实现, 本质是 Spring 为目标类创建代理对象, 因此在同一个类里面内部调用事务管理方法必须通过代理对象来调用
```java
IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
return proxy.createVoucherOrder(voucherId);
```

## 分布式锁

但其实仍然存在问题: 传统的线程锁, 只能锁住单机的多线程; 而在**集群环境**下, 有多个服务器, 每个服务器有自己独立的 jvm, 虽然跑的代码是一样的, 但实际不是一个锁对象, 因此没有锁住

于是可能发生: 一个用户的多个下单请求分配到了不同的机器上, 不同机器上各一个线程完成了下单操作

所以我们需要使用**分布式锁: 本质就是全局都是一把锁**

***

先补充一下使用 redis 实现**全局唯一 id 生成器**: 我们根据日期, 在 redis 中设置一个 key; 每需要一个 id, 就对这个 key 进行自增操作

为什么 redis 能实现全局唯一呢? 即为什么保证分布式系统下的 id 唯一呢? 因为全局都使用的是**一个 redis 实例**, 并且 redis 的自增操作是**原子性**的

***

因此, 基于 redis 的**操作原子性, 单实例共享特性, 高性能和高并发处理能力, 以及可扩展性**, 我们可以使用 redis 实现分布式锁: 利用 SETNX 命令, 在 redis 中设置一个 key 作为锁, 第一个通过 SETNX 成功设置这个 key 的线程即被视为获取到锁 (和解决缓存击穿, 只能让一个线程去进行缓存重建一样)

- 在解决缓存击穿那使用的锁, 对应的 key 是 lock:shop:shopId, 这个 key 显然是全局唯一的, 所有想查询 shopId 对应的商户信息的线程都是要去获取这把锁, 这一点非常正确; 但是我们**没怎么考虑释放锁的细节, 以及出现异常的情况**: 为了防止死锁, 我们给 key 设置了过期时间; 但是如果拿到锁的线程 A 因异常情况阻塞, 锁就会自动释放, 其他线程比如线程 B 就可以去获取这把锁, 线程 B 获取到锁但回头又被缓过神来的线程 A 给删除了, 使得线程 C 又可以去获取这把锁...最终可能导致大量请求直接访问数据库

那么, 现在我该如何合理设置锁, 以保证全局同一个下单请求都是获取这一把锁, 并且释放锁的时候不会误删别人的锁呢?

- key 的名称: lock:order:voucherId:userId, 对应可以确定订单的两个信息 userId 和 voucherId, **保证全局同一个下单请求都是获取这一把锁**

- 采用 **UUID + 线程 id** 作为 key 的 value, 在释放锁时, 我们要检查 UUID + 线程 id 与 value 是否一致, 一致才释放锁; 其中: **UUID 是用来区分不同机器的, 而线程 id 是用来区分同一台机器上的不同线程**, 这样保证了一个线程对应的 value 是唯一的, 保证了一个线程只会去释放自己的锁

然后我们又遇到了问题 ~~(其实感觉这个项目全是问题, 到最后也没解决)~~
- 根据 value 判断是不是自己的锁和删除认为是自己的锁是**两个操作, 不具有原子性**: 这里有两个相同请求的线程 A 和 B, 它们要获取的锁的名称一样; 如果线程 A 刚判断完属于它的锁, 这把锁就自动释放了, 线程 B 又立马设置了一把属于线程 B 的锁, 此时线程 A 要删除的就不是它的锁了

- 因此我们引入 **lua 脚本**, 通过 `execute(RedisScript<T> script, List<K> keys, Object... args)` 调用, 这样就可以原子性的完成 get 和 del 两个操作了
```lua
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
```

## Redisson 分布式锁

基于 SETNX 实现的分布式锁有以下问题:
- **不可重入**: 同一个线程无法多次获取同一把锁, 容易发生死锁
- **不可重试**: 获取锁没有设计重试机制
- **超时释放**: 只设置了一个过期时间, 如果中间的业务逻辑耗时过长或出现异常, 锁就会自动释放, 导致锁失效, 影响业务逻辑
- **主从一致性**: 如果 redis 提供了主从集群, 当我们向集群写数据时, 主机需要异步的将数据同步给从机, 而万一在同步过去之前, 主机宕机了, 就会出现死锁问题

所以我们使用 Redisson 分布式锁, 它可以重入锁, 有锁重试机制, 有锁续约机制, 还提供 MutiLock 以保证主从一致性
```java
RLock lock = redissonClient.getLock("lock:order:" + voucherId + ":" + userId);
boolean getLock = lock.tryLock();
if (!getLock) {
    log.error("不允许重复下单!");
    return;
}

try {
    // 事务管理方法, 必须使用代理对象调用
    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    proxy.createVoucherOrder(voucherOrder);
} finally {
    lock.unlock();
}
```

### 可重入锁原理

- RedissonLock.tryLockInnerAsync
```java
<T> RFuture<T> tryLockInnerAsync(long waitTime, long leaseTime, TimeUnit unit, long threadId, RedisStrictCommand<T> command) {
    return this.evalWriteSyncedNoRetryAsync(this.getRawName(), LongCodec.INSTANCE, command, "if ((redis.call('exists', KEYS[1]) == 0) or (redis.call('hexists', KEYS[1], ARGV[2]) == 1)) then redis.call('hincrby', KEYS[1], ARGV[2], 1); redis.call('pexpire', KEYS[1], ARGV[1]); return nil; end; return redis.call('pttl', KEYS[1]);", Collections.singletonList(this.getRawName()), new Object[]{unit.toMillis(leaseTime), this.getLockName(threadId)});
}
```

```lua
if ((redis.call('exists', KEYS[1]) == 0) or (redis.call('hexists', KEYS[1], ARGV[2]) == 1)) then
    redis.call('hincrby', KEYS[1], ARGV[2], 1);
    redis.call('pexpire', KEYS[1], ARGV[1]);
    return nil;
end;
return redis.call('pttl', KEYS[1]);
```

- 锁对应的 key 是一个哈希结构, 哈希的 key 是线程的唯一标识, value 是线程的重入次数
- 如果 key 不存在, 或者 key 存在且线程的唯一标识存在, 就重入次数 +1, 并更新过期时间 (而释放锁时, 就会重入次数 -1, 重入次数为 0 才真正释放锁)
- 否则, 锁被别的线程持有, 返回这个 key 的过期时间

### 重试机制

- RedissonLock.lock: 用于尝试获取分布式锁, 如果锁不可用, 线程会被阻塞, 直到成功获取到锁或者被中断 (取决于 interruptibly 参数)
```java
private void lock(long leaseTime, TimeUnit unit, boolean interruptibly) throws InterruptedException {
    long threadId = Thread.currentThread().getId();
    Long ttl = this.tryAcquire(-1L, leaseTime, unit, threadId);
    // 如果没获取到锁
    if (ttl != null) {
        CompletableFuture<RedissonLockEntry> future = this.subscribe(threadId);
        this.pubSub.timeout(future);
        RedissonLockEntry entry;
        if (interruptibly) {
            entry = (RedissonLockEntry)this.commandExecutor.getInterrupted(future);
        } else {
            entry = (RedissonLockEntry)this.commandExecutor.get(future);
        }

        try {
            while(true) {
                ttl = this.tryAcquire(-1L, leaseTime, unit, threadId);
                if (ttl == null) {
                    return;
                }

                if (ttl >= 0L) {
                    try {
                        entry.getLatch().tryAcquire(ttl, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException var14) {
                        if (interruptibly) {
                            throw var14;
                        }

                        entry.getLatch().tryAcquire(ttl, TimeUnit.MILLISECONDS);
                    }
                } else if (interruptibly) {
                    entry.getLatch().acquire();
                } else {
                    entry.getLatch().acquireUninterruptibly();
                }
            }
        } finally {
            this.unsubscribe(entry, threadId);
        }
    }
}
```

### 续约机制

- RedissonLock.tryAcquireAsync: 调用了 RedissonBaseLock.scheduleExpirationRenewal
```java
private RFuture<Long> tryAcquireAsync(long waitTime, long leaseTime, TimeUnit unit, long threadId) {
    RFuture ttlRemainingFuture;
    if (leaseTime > 0L) {
        // 使用设定的持有时间
        ttlRemainingFuture = this.tryLockInnerAsync(waitTime, leaseTime, unit, threadId, RedisCommands.EVAL_LONG);
    } else {
        // 使用默认的持有时间
        ttlRemainingFuture = this.tryLockInnerAsync(waitTime, this.internalLockLeaseTime, TimeUnit.MILLISECONDS, threadId, RedisCommands.EVAL_LONG);
    }

    CompletionStage<Long> s = this.handleNoSync(threadId, ttlRemainingFuture);
    RFuture<Long> ttlRemainingFuture = new CompletableFutureWrapper(s);
    CompletionStage<Long> f = ttlRemainingFuture.thenApply((ttlRemaining) -> {
        if (ttlRemaining == null) {
            if (leaseTime > 0L) {
                this.internalLockLeaseTime = unit.toMillis(leaseTime);
            } else {
                // 触发看门狗续约逻辑, 交给了 RedissonBaseLock
                this.scheduleExpirationRenewal(threadId);
            }
        }

        return ttlRemaining;
    });
    return new CompletableFutureWrapper(f);
}
```

然后从 RedissonBaseLock.scheduleExpirationRenewal -> LockRenewalScheduler.renewLock -> LockTask.add -> RenewalTask.add 一直往上找, **最后也没看明白**, 猜想应该关注的是下面几个方法:

- RenewalTask.add
    ```java
    final void add(String rawName, String lockName, long threadId, LockEntry entry) {
        this.addSlotName(rawName);
        LockEntry oldEntry = (LockEntry)this.name2entry.putIfAbsent(rawName, entry);
        if (oldEntry != null) {
            oldEntry.addThreadId(threadId, lockName);
        } else if (this.tryRun()) {
            this.schedule();
        }
    }
    ```
    - 主要用于将锁的相关信息添加到看门狗机制的管理结构中, 并且在必要时启动看门狗的定时续约任务

- RenewalTask.schedule
    ```java
    public void schedule() {
        if (this.running.get()) {
            long internalLockLeaseTime = this.executor.getServiceManager().getCfg().getLockWatchdogTimeout();
            this.executor.getServiceManager().newTimeout(this, internalLockLeaseTime / 3L, TimeUnit.MILLISECONDS);
        }
    }
    ```
    - 检查 `running` 标志是否为 `true`, 如果是, 则使用 `executor` 的 `newTimeout` 方法创建一个定时任务, 在 `internalLockLeaseTime / 3` 毫秒后执行当前任务

- RenewalTask.run
    ```java
    public void run(Timeout timeout) {
        if (!this.executor.getServiceManager().isShuttingDown()) {
            CompletionStage<Void> future = this.execute();
            future.whenComplete((result, e) -> {
                if (e != null) {
                    this.log.error("Can't update locks {} expiration", this.name2entry.keySet(), e);
                    this.schedule();
                } else {
                    this.schedule();
                }
            });
        }
    }
    ```
    - `CompletionStage<Void> future = this.execute();` 执行续约操作, execute 方法会根据具体情况调用 renew 或 renewSlots 方法来尝试更新锁的过期时间
    - `future.whenComplete` 当续约操作完成后，无论成功还是失败, 都会调用 schedule 方法再次安排下一次续约任务, 体现了持续续约逻辑

### 主从一致性原理

大概就是每一个节点上都有一把锁, 然后 MultiLock 允许你将多个独立的锁组合成一个逻辑锁, **在获取锁时, 只有当所有单个锁都成功获取时, 整个 MultiLock 才算获取成功**, 只要有一个节点拿不到, 都不能算是加锁成功, 保证了加锁的可靠性 (如果有一个失败, 也会立刻释放已经获取的锁)

MultiLock 确保了多个锁的获取和释放操作的原子性, 在主从架构下, 即使某个从节点出现短暂的同步延迟 (主从复制过程中可能存在延迟), MultiLock 的机制也能保证加锁和解锁操作要么全部成功, 要么全部失败, 不会出现部分节点加锁成功而部分节点加锁失败的情况, 从而维护了主从节点之间操作的一致性

## 秒杀优化与 redis 消息队列

整个流程中有很多数据库操作: 
- 从数据库查询秒杀时间和秒杀库存也判断秒杀资格
- 创建订单时从数据库查询订单保证一人一单
- 最后更新库存和保存订单到数据库

问题在于: **这些数据库操作比较耗时, 而且是串行执行**

为了提高效率, 我们从两方面来优化:
1. **快速判断秒杀资格**: 包括秒杀时间, 秒杀库存和一人一单的判断
2. 将不可避免的数据库操作: 更新库存和保存订单, **开一个独立线程, 异步地去执行**

核心思路就是: 只要我们知道要进行什么操作, 我们后边慢慢做这些操作就可以了, **但是我们必须保证判断的逻辑没有问题**, 也就是第一步非常关键

第一步要想快, 不难想到使用 redis:
- 我们将秒杀优惠券的开始时间, 结束时间也存到 redis 中, 并且还要存储库存: 因为现在数据库里的库存更新和我的第一步判断秒杀资格的数据不同步了, 我必须在第一步里就检查和更新库存; 最后我根据结束时间给三个 key 设置了过期时间
```java
// 保存秒杀优惠券到 redis 中
stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
stringRedisTemplate.opsForValue().set(SECKILL_BEGINTIME_KEY + voucher.getId(), voucher.getBeginTime().toString());
stringRedisTemplate.opsForValue().set(SECKILL_ENDTIME_KEY + voucher.getId(), voucher.getEndTime().toString());

// 计算有效时间
LocalDateTime now = LocalDateTime.now();
LocalDateTime endTime = voucher.getEndTime();
long expireTime = Duration.between(now, endTime).getSeconds();

// 设置过期时间
stringRedisTemplate.expire(SECKILL_STOCK_KEY + voucher.getId(), expireTime, TimeUnit.SECONDS);
stringRedisTemplate.expire(SECKILL_BEGINTIME_KEY + voucher.getId(), expireTime, TimeUnit.SECONDS);
stringRedisTemplate.expire(SECKILL_ENDTIME_KEY + voucher.getId(), expireTime, TimeUnit.SECONDS);
```

- 而在 VoucherOrderServiceImpl 中, 先进行秒杀时间的判断, 因为 key 可能过期, 所以要判断 null
```java
private boolean isSeckillTimeValid(Long voucherId) {
    String beginTimeStr = stringRedisTemplate.opsForValue().get(SECKILL_BEGINTIME_KEY + voucherId);
    String endTimeStr = stringRedisTemplate.opsForValue().get(SECKILL_ENDTIME_KEY + voucherId);

    // 如果是 null, 说明已经过期
    if (beginTimeStr == null || endTimeStr == null) {
        return false;
    }

    LocalDateTime beginTime = LocalDateTime.parse(beginTimeStr);
    LocalDateTime endTime = LocalDateTime.parse(endTimeStr);
    LocalDateTime now = LocalDateTime.now();

    return now.isAfter(beginTime) && now.isBefore(endTime);
}
```

- 然后就要进行库存和一人一单的判断, 显然我们也要把订单存储到 redis 中, 而这些涉及到的操作有: 查询库存, 查询订单, 修改库存, 新增订单; 多个操作, 我们需要原子性的完成, 因此要引入 lua 脚本
```lua
local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 检查库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 检查是否重复下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 减少库存
redis.call('incrby', stockKey, -1)

-- 用户下单记录
redis.call('sadd', orderKey, userId)

return 0
```

***

那如何**异步**的完成创建订单相关的数据库操作呢? 这里**采用 redis 中的 stream 实现消息队列**

如果使用 stream 的 XREAD 命令, 可以指定 id 为 0 读取第一条消息或者指定 id 为 $ 读取最新一条消息, 其特点为:
- 消息可回溯
- 一个消息可以被多个消费者读取
- 可以阻塞读取
- 有消息漏读的风险

如果一直有消息发送进来, 并且一直 XREAD 读取最新的一条, 就可能会有消息漏读; 一个消息被多个消费者重复消费, 效率低

因而使用 **XREADGROUP** 命令, 其引入了消费者组的概念, 将多个消费者划分到一个组中, 监听同一个队列, 有以下特点:
- **消息分流**: 消息分流给消费者组中多个消费者, 而不是重复消费
- **消息标示**: 消费者组会为每个消费者记录最后一个处理的消息的位置, 当消费者宕机重启后, 消费组能够从上次记录的位置继续发送消息给该消费者, 从而确保消息不会遗漏
- **消息确认**: 消息被消费后会进入 pending list, 只有被 **XACK** 命令确认后, 才会从 pending list 中移除

**XREADGROUP 可以指定 id 为 > 读取最新一条消息, 而当指定 id 为 0 时, 就会从 pending list 中读取待确认的消息**, 下面是 redis 命令文档原文:

The ID to specify in the STREAMS option when using XREADGROUP can be one of the following two:

- The special > ID, which means that the consumer want to receive only messages that were never delivered to any other consumer. It just means, give me new messages.
- Any other ID, that is, 0 or any other valid ID or incomplete ID (just the millisecond time part), will have the effect of returning entries that are pending for the consumer sending the command with IDs greater than the one provided. So basically if the ID is not >, then the command will just let the client access its pending entries: messages delivered to it, but not yet acknowledged. Note that in this case, both BLOCK and NOACK are ignored.

Normally you use the command like that in order to get new messages and process them. In pseudo-code:

```
WHILE true
    entries = XREADGROUP GROUP $GroupName $ConsumerName BLOCK 2000 COUNT 10 STREAMS mystream >
    if entries == nil
        puts "Timeout... try again"
        CONTINUE
    end

    FOREACH entries AS stream_entries
        FOREACH stream_entries as message
            process_message(message.id,message.fields)

            # ACK the message as processed
            XACK mystream $GroupName message.id
        END
    END
END
```

In this way the example consumer code will fetch only new messages, process them, and acknowledge them via XACK. However the example code above is not complete, because it does not handle recovering after a crash. What will happen if we crash in the middle of processing messages, is that our messages will remain in the pending entries list, so we can access our history by giving XREADGROUP initially an ID of 0, and performing the same loop. Once providing an ID of 0 the reply is an empty set of messages, we know that we processed and acknowledged all the pending messages: we can start to use > as ID, in order to get the new messages and rejoin the consumers that are processing new things.

所以使用消息队列的整体逻辑为: 
- 在判断完秒杀资格后, 要发送消息到消息队列, 所以要修改 lua 脚本
```lua
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 检查库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 检查是否重复下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 减少库存
redis.call('incrby', stockKey, -1)

-- 用户下单记录
redis.call('sadd', orderKey, userId)

-- 发送消息到消息队列
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0
```
- 开启一个独立线程, 监听消息队列, 异步完成创建订单相关数据库操作
    - 当处理消息不出现异常时, 一直处理最新的消息 (指定 id 为 >)
    - 出现异常时, 就去处理 pending list 中的消息 (指定 id 为 0), 直至 pending list 为空
```java
/*
 * 独立线程, 监听消息队列, 异步完成创建订单相关数据库操作
 */
private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

@PostConstruct
private void init() {
    SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
}

/*
 * 基于 redis stream 的消息队列
 * 非异常情况下, 一直处理最新的消息
 * 异常情况下, 指定 id 为 0, 是去处理 pending list 中的消息
 */
private class VoucherOrderHandler implements Runnable {
    private final String QUEUE_NAME = "stream.orders";

    public void initStream() {
        Boolean exists = stringRedisTemplate.hasKey(QUEUE_NAME);
        if (BooleanUtil.isFalse(exists)) {
            log.info("stream 不存在, 开始创建 stream");
            stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, ReadOffset.latest(), "g1");
            log.info("stream 和 group 创建完毕");
            return;
        }

        StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(QUEUE_NAME);
        if (groups.isEmpty()) {
            log.info("group 不存在, 开始创建 group");
            stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, ReadOffset.latest(), "g1");
            log.info("group 创建完毕");
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                // 初始化 stream
                initStream();

                // 获取消息队列中的订单信息
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object> > list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                );

                if (list == null || list.isEmpty()) {
                    continue;
                }

                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                // 创建订单
                handleVoucherOrder(voucherOrder);
                
                // 确认消息
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());                    
            } catch (Exception e) {
                log.error("创建订单异常", e);
                // 出现异常, 去处理 pending list 中的消息
                handlePendingList();
            }
        }
    }

    public void handlePendingList() {
        while (true) {
            try {
                // 获取 pending list 中的订单信息
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object> > list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );

                if (list == null || list.isEmpty()) {
                    break;
                }

                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                handleVoucherOrder(voucherOrder);
                
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());       
            } catch (Exception e) {
                log.error("创建订单异常", e);
            }
        }
    }
}

/*
 * 创建订单
 */
private void handleVoucherOrder(VoucherOrder voucherOrder) {
    Long userId = voucherOrder.getUserId();
    Long voucherId = voucherOrder.getVoucherId();
    
    RLock lock = redissonClient.getLock("lock:order:" + voucherId + ":" + userId);
    boolean getLock = lock.tryLock();
    if (!getLock) {
        log.error("不允许重复下单!");
        return;
    }

    try {
        // 事务管理方法, 必须使用代理对象调用
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        proxy.createVoucherOrder(voucherOrder);
    } finally {
        lock.unlock();
    }
}
```

***

在 lua 脚本中, 我们就检查了库存和一人一单, 保证了一个用户对一个秒杀券的订单只会在库存充足的情况下创建, 并且**只发送给消息队列一次**

所以, 这几个操作看起来似乎就不太必要:
- handleVoucherOrder 中使用 Redisson 分布式锁保证一人一单
- createVoucherOrder 中进行数据库查询保证一人一单
- createVoucherOrder 中修改库存时使用乐观锁保证库存

**但是转念一想**, 问题可能出在消息的处理逻辑上: 如果有一个消息被消费并且被成功处理了, 但是 **XACK 确认却出现了异常** (比如 redis 突然宕机了), 那这个消息就会在 pending list 中, 就可能又被成功处理了一次

所以, 最后在 createVoucherOrder 中进行数据库查询保证一人一单和使用乐观锁保证库存, 好像还是很有必要的 (Redisson 分布式锁保证一人一单, 目前感觉就没什么用)

## TODO

除了上述的部分, 写了部分点赞相关的逻辑后, 感觉后面的部分没啥意思, 打算就不写了

然后找到的一些可能可以扩展的地方:

- 用户登录限流
    - 限制请求次数?
    - 限制失败次数?

- 多级缓存
    - JVM 进程缓存?
        - ConcurrentHashMap?
        - Caffeine?
    - 页面缓存?
    
- 预热缓存
    - 项目启动, Spring 注入 StringRedisTemplate 时预热缓存?
    - 使用 xxl-job 定时预热缓存?

- 更换 mq 替换 redis stream
    - 消息可靠性?
    - 消息幂等性?
    - 重试机制?
    - 如何防止消息堆积?

- 秒杀限流
    - 上游拦截请求?
    - 令牌桶限流?
    - redis 限流?
    - 压测秒杀接口, 算出塌陷区, 用 sentinel 限流熔断?

- 不同场景, 采取不同的策略以保证数据一致性
    - 更新数据库后删除缓存? 延迟双删?
    - Cannal 监听 binlog, 使用 mq 异步更新缓存?

- 更改整个点赞相关逻辑
    - ?

- 异常处理
    - ?

- 日志处理
    - ?