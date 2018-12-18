package com.rbkmoney.fraudbusters.domain;

import com.rbkmoney.fraudbusters.constant.Level;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RuleTemplate {

    private String globalId;
    private String localId;
    private Level lvl;
    private String template;

}