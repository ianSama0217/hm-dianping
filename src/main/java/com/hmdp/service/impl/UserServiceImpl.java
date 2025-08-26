package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;

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

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 檢查手機號碼格式
        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手機號碼格式錯誤");
        }

        // 檢查驗證碼
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();

        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("驗證碼錯誤");
        }

        // 根據手機號碼查詢用戶是否存在
        User user = query().eq("phone", phone).one();

        // 不存在則創建新用戶
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }

        // 保存用戶信息到session
        session.setAttribute("user", user);

        return Result.ok();
    }

}
