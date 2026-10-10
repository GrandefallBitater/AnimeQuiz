package com.example.animequiz.controller.websocket;

import com.example.animequiz.entity.greetingsDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

@Controller
@Slf4j
public class GreetingController {

    @MessageMapping("/hello")
    @SendTo("/game/greetings")
    public greetingsDTO greeting(greetingsDTO message) throws Exception {
        greetingsDTO greetingsDTO1 = new greetingsDTO("hi " +  HtmlUtils.htmlEscape(message.getName()));
        log.info(greetingsDTO1.getName());
        return greetingsDTO1;
    }

    @GetMapping("/lobby")
    public String lobby() {
        return "lobby";
    }

}
