package com.example.animequiz.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class greetingsDTO {

    private String name;

    public greetingsDTO(String s) {
        this.name = s;
    }
}