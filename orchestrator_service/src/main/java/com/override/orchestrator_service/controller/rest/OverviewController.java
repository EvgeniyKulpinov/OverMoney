package com.override.orchestrator_service.controller.rest;

import com.override.orchestrator_service.model.OverMoneyAccount;
import com.override.orchestrator_service.model.User;
import com.override.orchestrator_service.service.OverMoneyAccountService;
import com.override.orchestrator_service.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/overview")
public class OverviewController {
    @Autowired
    private UserService userService;
    @Autowired
    private OverMoneyAccountService overMoneyAccountService;

//    @GetMapping()
//    public OverMoneyAccount getAccount(@RequestParam(required = true) String accessToken) {
//        User user = userService.;
//        return overMoneyAccountService.;
//    }
}
