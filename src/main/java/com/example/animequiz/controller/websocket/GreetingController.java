package com.example.animequiz.controller.websocket;

import com.example.animequiz.entity.greetingsDTO;
import com.example.animequiz.validation.InputValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Slf4j
public class GreetingController {

    @MessageMapping("/hello")
    @SendTo("/game/greetings")
    public greetingsDTO greeting(greetingsDTO message) {
        String rawName = message == null ? null : message.getName();

        String name;
        try {
            name = InputValidation.normalizeLobbyName(rawName);
        } catch (IllegalArgumentException e) {
            name = "guest";
        }

        greetingsDTO response = new greetingsDTO("hi " + name);
        log.info("{}", response.getName());
        return response;
    }

    @GetMapping("/lobby")
    public String lobby() {
        return "lobby";
    }
}
