package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        //缓存穿透
        Shop shop = lock(id);
        return Result.ok(shop);
    }

    /**
     * 缓存穿透
     */
    public Shop chuantou(Long id){
        //        从redis获取，如果没有，从mysql获取并存入redis
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJson)){
//            缓存查到
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){
            return null;
        }
//        缓存没查到，查数据库
        Shop shop = getById(id);
        if(shop==null){
//            解决缓存穿透的问题，缓存空数据，过期时间短一点
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",2,TimeUnit.MINUTES);
            return null;
        }
//        存入缓存
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    /**
     * 互斥锁,解决缓存击穿的问题
     * @return
     */
    public Shop lock(Long id){
        //        从redis获取，如果没有，从mysql获取并存入redis
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJson)){
//            缓存查到
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){
            return null;
        }
//        互斥锁
//        获取锁，
        Shop shop = null;
        try {
            boolean lock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
//        获取失败稍等重试，
            while (!lock){
                Thread.sleep(50);
                tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            }
//        获取成功从数据库写入到缓存中，
            //        缓存没查到，查数据库
            shop = getById(id);
            if(shop==null){
    //            解决缓存穿透的问题，缓存空数据，过期时间短一点
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",2,TimeUnit.MINUTES);
                return null;
            }
//        存入缓存
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //        操作后释放锁
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }




        return shop;
    }
    //互斥锁
    private boolean tryLock(String key){

        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return b;
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    @Override
    public Result queryShopByType(Integer typeId, Integer current) {
//        String pageJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE + typeId);
//        if(StrUtil.isNotBlank(pageJson)){
//            Shop page = JSONUtil.toBean(pageJson, Shop.class);
//            return Result.ok(page);
//        }
//         根据类型分页查询
        Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
//         返回数据
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE+typeId, JSONUtil.toJsonStr(page));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        // 写入数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
