package com.sai.wise.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A recipient account created at Wise; its id becomes the transfer's targetAccount. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecipientResponse(
        Long id,
        String currency,
        String type,
        String accountHolderName,
        String country
) {}
