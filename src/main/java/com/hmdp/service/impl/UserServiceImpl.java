package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpSession;

import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 檢查手機號碼格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手機號碼格式錯誤");
        }

        // 生成驗證碼
        String code = RandomUtil.randomNumbers(6);

        // 保存驗證碼到session
        session.setAttribute("code", code);

        // 發送驗證碼
        log.debug("驗證碼已發送，驗證碼：{}", code);

        return Result.ok();
    }

}
