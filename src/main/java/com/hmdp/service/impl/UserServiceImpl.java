package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
//    @Slf4j
    public Result sendCode(String phone, HttpSession session) {
//        1.校验手机号符合规范
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(!phoneInvalid){
            //        2.生成验证码，保存到redis
            String code = RandomUtil.randomNumbers(6);
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
//            session.setAttribute("code",code);
//        3.发送并返回成功
            log.debug(code);
            return Result.ok();
        }
        else return Result.fail("手机号无效");

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        获取session的验证码，
//        String code = (String) session.getAttribute("code");
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+loginForm.getPhone());
//        校验是否一致，
        if( !loginForm.getCode().equals(code)){
            return Result.fail("验证码不一致");
        }
        //查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //是新用户则新建，
        if(user==null){
            user = createUesr(loginForm.getPhone());
        }
        //        生成并保存token和用户信息到redis,设置过期时间
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        userMap.forEach((k,v)->{
            userMap.put(k,StrUtil.toString(v));});
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.SECONDS);
        return Result.ok(token);

    }
    private User createUesr(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_"+phone);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
//        保存到数据库
        save(user);
        return user;

    }
}
