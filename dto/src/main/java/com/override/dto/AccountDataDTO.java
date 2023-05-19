package com.override.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AccountDataDTO {
    private Long chatId;
    private List<CategoryDTO> categories;
    private List<TransactionResponseDTO> transactions;
}
