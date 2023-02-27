package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl voucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列，后续可以使用mq
    //private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //bean实例化之前执行，让类一初始化就执行这个任务
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
    }

    private class voucherOrderHandler implements Runnable {
        private String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP G1 C1 COUNT 1 BLOCK 2000 STREAM streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //log.info("list集合{}", list.isEmpty());
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //3.失败进行重试，说明没有消息，继续循环
                        continue;
                    }

                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> recordValue = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                    //4.成功则创建订单
                    handlerVoucherOrder(voucherOrder);
                    //5.ACK确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                }catch (Exception e){
                    log.error("处理订单异常", e.getMessage());
                    //读取消息异常
                    try {
                        handlerPendingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        private void handlerPendingList() throws InterruptedException {
            while (true){
                try {
                    //1.获取pendinglist中的订单信息 XREADGROUP GROUP G1 C1 COUNT 1 BLOCK 2000 STREAM streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //3.失败进行重试，说明pendingList没有消息，结束循环
                        break;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> recordValue = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                    //4.成功则创建订单
                    handlerVoucherOrder(voucherOrder);
                    //5.ACK确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("读取pendinglist消息异常", e.getMessage());
                    Thread.sleep(20);
                }
            }
        }
    }
    //@PostConstruct //bean实例化之前执行
    //private void init() {
    //    SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
    //}
    //
    //private class voucherOrderHandler implements Runnable {
    //    @Override
    //    public void run() {
    //        while (true) {
    //            try {
    //                //1.获取阻塞队列中的订单信息
    //                VoucherOrder voucherOrder = orderTask.take();
    //                //2.创建订单
    //                handlerVoucherOrder(voucherOrder);
    //            } catch (InterruptedException e) {
    //                log.error("处理订单异常", e.getMessage());
    //            }
    //        }
    //    }
    //}
    private IVoucherOrderService proxy;
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();

        //4.判断是否获取锁
        if (!isLock) {
            //获取锁失败，返回错误，或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }



    @Override
    //事务
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        String userId = UserHolder.getUser().getId().toString();
        //订单id
        long orderId = redisIdWorker.nextId("order");

        log.info("voucherId {}", voucherId + "userId:" + userId);
        //1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId,
                String.valueOf(orderId)
        );
        //2. 得到返回结果
        int r = result.intValue();
        if (r != 0) {
            //2.1 为1无购买资格，为2重复下单
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0有购买资格，将订单信息放入到阻塞队列

        //3.获取代理对象
        //proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4. 返回订单id
        return Result.ok(orderId);
    }

    //@Override
    ////事务
    //public Result seckillVoucher(Long voucherId) {
    //    String userId = UserHolder.getUser().getId().toString();
    //    System.out.println(userId);
    //    log.info("voucherId {}", voucherId + "userId:" + userId);
    //    //1. 执行lua脚本
    //    Long result = stringRedisTemplate.execute(
    //            SECKILL_SCRIPT,
    //            Collections.emptyList(),
    //            voucherId.toString(),
    //            userId
    //    );
    //    //2. 得到返回结果
    //    int r = result.intValue();
    //    if (r != 0) {
    //        //2.1 为1无购买资格，为2重复下单
    //        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    //    }
    //    //2.2 为0有购买资格，将订单信息放入到阻塞队列
    //    VoucherOrder order = new VoucherOrder();
    //    //订单id
    //    long orderId = redisIdWorker.nextId("order");
    //    order.setId(orderId);
    //    //用户id
    //    order.setUserId(Long.valueOf(userId));
    //    //代金券id
    //    order.setVoucherId(voucherId);
    //    //存入阻塞队列
    //    orderTask.add(order);
    //    //3.获取代理对象
    //    proxy = (IVoucherOrderService) AopContext.currentProxy();
    //    //4. 返回订单id
    //    return Result.ok(orderId);
    //}

    //@Override
    // //事务
    //public Result seckillVoucher(Long voucherId) {
    //
    //    //1.查询优惠券
    //    SeckillVoucher voucher = voucherService.getById(voucherId);
    //    //2.判断秒杀是否开始
    //    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
    //        //尚未开始
    //        return Result.fail("秒杀尚未开始");
    //    }
    //    //3.判断秒杀是否结束
    //    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
    //        return Result.fail("秒杀已经结束");
    //    }
    //    //4.判断库存是否充足
    //    if (voucher.getStock() < 1){
    //        return Result.fail("库存不足");
    //    }
    //
    //
    //
    //    Long userId = UserHolder.getUser().getId();
    //
    //    //创建锁对象
    //    //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    //    RLock lock = redissonClient.getLock("lock:order:" + userId);
    //    //获取锁
    //    boolean isLock = lock.tryLock();
    //
    //    //判断是否获取锁
    //    if (!isLock){
    //        //获取锁失败，返回错误，或重试
    //        return Result.fail("不允许重复下单");
    //    }
    //    try {
    //        IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
    //        return currentProxy.createVoucherOrder(voucherId);
    //    } finally {
    //        lock.unlock();
    //    }
    //    ////因为是需要从常量池拿出相同对象，所以需要使用intern方法
    //    //synchronized (userId.toString().intern()) {
    //    //    //获取代理对象
    //    //    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //    //    return proxy.createVoucherOrder(voucherId); //使用this会造成事务失效，需要拿到事务代理 对象进行执行方法。
    //    //}
    //}


    @Transactional //事务生效是因为，拿到对象的代理类进行事务处理。
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //6.2用户id
        Long userId = voucherOrder.getUserId();

        //因为是需要从常量池拿出相同对象，所以需要使用intern方法
        //在此处锁的话，会造成事务还没提交就释放锁，会造成重复用户购买，需要锁整个方法
        //synchronized (userId.toString().intern()) {
        Long voucherId = voucherOrder.getVoucherId();
        //5.实现一人一单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

        //5.1 判断是否大于1
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }

        //6.扣减库存
        boolean success = voucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) //where id = ? and stock > 0
                .update();
        if (!success) {
            //扣减失败
            log.error("库存不足");
            return;
        }
        ////7.创建订单
        //VoucherOrder order = new VoucherOrder();
        ////7.1订单id
        //long orderId = redisIdWorker.nextId("order");
        //order.setId(orderId);
        //
        ////7.2 用户id
        //order.setUserId(userId);
        ////7.3代金券id
        //order.setVoucherId(voucherId);

        save(voucherOrder);
    }
}
