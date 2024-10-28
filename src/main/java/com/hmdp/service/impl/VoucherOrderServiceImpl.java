package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;


    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
//        查询优惠券，
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("没有秒杀券");
        }
//        判断秒杀是否开始，
        if (!voucher.getBeginTime().isBefore(LocalDateTime.now()) || !voucher.getEndTime().isAfter(LocalDateTime.now())) {
            return Result.fail("现在不是秒杀时间");
        }
//        判断库存是否充足，
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
//        将 userId.toString().intern() 作为锁对象。这意味着，只有持有相同 userId 的线程才能进入同步块，其它持有不同 userId 的线程可以并行执行。
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLocked = lock.tryLock();
        if(!isLocked){
//            获取锁失败
            return Result.fail("不允许重复下单");
        }

        try {
            IVoucherOrderService iVoucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            return iVoucherOrderService.createOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }

    @Transactional
    public Result createOrder(Long voucherId) {
        //        一人一单
        Integer count = query().eq("user_id", UserHolder.getUser().getId()).eq("voucher_id", voucherId).count();
        if (count != 0) {
            return Result.fail("已下过单了");
        }
//        扣减秒杀券，
        boolean update = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!update) {
            return Result.fail("库存不足");
        }
//        秒杀创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        save(voucherOrder);

        return Result.ok(orderId);
    }


}

