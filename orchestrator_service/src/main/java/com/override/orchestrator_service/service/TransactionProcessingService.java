package com.override.orchestrator_service.service;

import com.override.dto.CategoryDTO;
import com.override.orchestrator_service.feign.RecognizerFeign;
import com.override.orchestrator_service.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.override.dto.TransactionMessageDTO;

import javax.management.InstanceNotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class TransactionProcessingService {

    @Autowired
    private OverMoneyAccountService overMoneyAccountService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private RecognizerFeign recognizerFeign;

    @Autowired
    private ExecutorService executorService;

    private enum AmountPositionType {
        AMOUNT_IN_FRONT,
        AMOUNT_BEHIND,
        AMOUNT_IN_FRONT_RU_LOCALE,
        AMOUNT_BEHIND_RU_LOCALE,
    }

    private enum RegularExpressions {
        SPACE(' '),
        RU_DECIMAL_DELIMITER(','),
        EN_DECIMAL_DELIMITER('.');

        private final char value;

        RegularExpressions(char value) {
            this.value = value;
        }
    }

    public Transaction processTransaction(TransactionMessageDTO transactionMessageDTO) throws InstanceNotFoundException {
        OverMoneyAccount overMoneyAccount = overMoneyAccountService
                .getOverMoneyAccountByChatId(transactionMessageDTO.getChatId());
        AmountPositionType type = checkTransactionType(transactionMessageDTO.getMessage());

        return Transaction.builder()
                .account(overMoneyAccount)
                .amount(getAmount(transactionMessageDTO.getMessage(), type))
                .message(getWords(transactionMessageDTO.getMessage(), type))
                .category(getTransactionCategory(transactionMessageDTO, overMoneyAccount, type))
                .date(transactionMessageDTO.getDate())
                .build();
    }

    private AmountPositionType checkTransactionType(String transactionMessage) throws InstanceNotFoundException {
        int firstIndexOfSpace = transactionMessage.indexOf(RegularExpressions.SPACE.value);
        int lastIndexOfSpace = transactionMessage.lastIndexOf(RegularExpressions.SPACE.value);
        if (firstIndexOfSpace == -1 || lastIndexOfSpace == -1) {
            throw new InstanceNotFoundException("Invalid message");
        }
        String firstWord = transactionMessage.substring(0, firstIndexOfSpace);
        String lastWord = transactionMessage.substring(lastIndexOfSpace + 1);
        try {
            Float.parseFloat(firstWord);
            return AmountPositionType.AMOUNT_IN_FRONT;
        } catch (NumberFormatException ignored) {}
        try {
            Float.parseFloat(firstWord.replace(RegularExpressions.RU_DECIMAL_DELIMITER.value,
                    RegularExpressions.EN_DECIMAL_DELIMITER.value));
            return AmountPositionType.AMOUNT_IN_FRONT_RU_LOCALE;
        } catch (NumberFormatException ignored) {
        }
        try {
            Float.parseFloat(lastWord);
            return AmountPositionType.AMOUNT_BEHIND;
        } catch (NumberFormatException ignored) {}
        try {
            Float.parseFloat(lastWord.replace(RegularExpressions.RU_DECIMAL_DELIMITER.value,
                    RegularExpressions.EN_DECIMAL_DELIMITER.value));
            return AmountPositionType.AMOUNT_BEHIND_RU_LOCALE;
        } catch (NumberFormatException ignored) {}
        throw new InstanceNotFoundException("Invalid message");
    }

    public void suggestCategoryToProcessedTransaction(TransactionMessageDTO transactionMessageDTO, UUID transactionId) throws InstanceNotFoundException {
        Transaction transaction = processTransaction(transactionMessageDTO);
        List<CategoryDTO> categories = categoryService.findCategoriesListByUserId(transactionMessageDTO.getUserId());
        executorService.execute(() -> {
            if (!categories.isEmpty()) {
                recognizerFeign.recognizeCategory(transaction.getMessage(), transactionId, categories);
            }
        });
    }

    private Category getTransactionCategory(TransactionMessageDTO transactionMessageDTO,
                                            OverMoneyAccount overMoneyAccount,
                                            AmountPositionType amountPositionType) throws InstanceNotFoundException {
        if (Objects.isNull(overMoneyAccount.getCategories()) ||
                Objects.isNull(getMatchingCategory(overMoneyAccount.getCategories(),
                        getWords(transactionMessageDTO.getMessage(), amountPositionType))) &&
                        Objects.isNull(getMatchingKeyword(overMoneyAccount.getCategories(),
                                getWords(transactionMessageDTO.getMessage(), amountPositionType)))) {
            return null;
        }
        Category matchingCategory = getMatchingCategory(overMoneyAccount.getCategories(),
                getWords(transactionMessageDTO.getMessage(), amountPositionType));
        if (matchingCategory != null) {
            return matchingCategory;
        }
        Keyword matchingKeyword = getMatchingKeyword(overMoneyAccount.getCategories(),
                getWords(transactionMessageDTO.getMessage(), amountPositionType));
        return matchingKeyword.getCategory();
    }

    private Category getMatchingCategory(Set<Category> categories, String words) {
        Category matchingCategory = null;
        for (Category category : categories) {
            if (words.equalsIgnoreCase(category.getName())) {
                matchingCategory = category;
                break;
            }
        }
        return matchingCategory;
    }

    private String getWords(String message, AmountPositionType type) throws InstanceNotFoundException {

        String words;
        int firstIndexOfSpace;
        int lastIndexOfSpace;
        switch (type) {
            case AMOUNT_IN_FRONT:
            case AMOUNT_IN_FRONT_RU_LOCALE:
                firstIndexOfSpace = message.indexOf(RegularExpressions.SPACE.value);
                words = message.substring(firstIndexOfSpace + 1);
                return words;
            case AMOUNT_BEHIND:
            case AMOUNT_BEHIND_RU_LOCALE:
                lastIndexOfSpace = message.lastIndexOf(RegularExpressions.SPACE.value);
                words = message.substring(0, lastIndexOfSpace);
                return words;
            default:
                throw new InstanceNotFoundException("No keywords present in the message");
        }
    }

    private Float getAmount(String message, AmountPositionType type) throws InstanceNotFoundException {
        String amountAsString;
        int firstIndexOfSpace;
        int lastIndexOfSpace;
        switch (type) {
            case AMOUNT_IN_FRONT:
                firstIndexOfSpace = message.indexOf(RegularExpressions.SPACE.value);
                amountAsString = message.substring(0, firstIndexOfSpace);
                return Float.parseFloat(amountAsString);
            case AMOUNT_BEHIND:
                lastIndexOfSpace = message.lastIndexOf(RegularExpressions.SPACE.value);
                amountAsString = message.substring(lastIndexOfSpace + 1);
                return Float.parseFloat(amountAsString);
            case AMOUNT_IN_FRONT_RU_LOCALE:
                firstIndexOfSpace = message.indexOf(RegularExpressions.SPACE.value);
                amountAsString = message.substring(0, firstIndexOfSpace);
                return Float.parseFloat(amountAsString.replace(RegularExpressions.RU_DECIMAL_DELIMITER.value,
                        RegularExpressions.EN_DECIMAL_DELIMITER.value));
            case AMOUNT_BEHIND_RU_LOCALE:
                lastIndexOfSpace = message.lastIndexOf(RegularExpressions.SPACE.value);
                amountAsString = message.substring(lastIndexOfSpace + 1);
                return Float.parseFloat(amountAsString.replace(RegularExpressions.RU_DECIMAL_DELIMITER.value,
                        RegularExpressions.EN_DECIMAL_DELIMITER.value));
            default:
                throw new InstanceNotFoundException("No amount stated");
        }
    }

    private Keyword getMatchingKeyword(Set<Category> categories, String words) {
        Keyword matchingKeyword = null;
        outer:
        for (Category category : categories) {
            Set<Keyword> keywordsSet = category.getKeywords();
            for (Keyword keyword : keywordsSet) {
                if (words.equalsIgnoreCase(keyword.getKeywordId().getName())) {
                    matchingKeyword = keyword;
                    break outer;
                }
            }
        }
        return matchingKeyword;
    }
}