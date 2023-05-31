package com.override.orchestrator_service.service;

import com.override.orchestrator_service.exception.CategoryNotFoundException;
import com.override.orchestrator_service.exception.TransactionNotFoundException;
import com.override.orchestrator_service.model.Category;
import com.override.orchestrator_service.model.Transaction;
import com.override.orchestrator_service.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceTest {

    @InjectMocks
    private TransactionService transactionService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CategoryService categoryService;

    @Test
    public void setTransactionCategoryThrowExceptionWhenCategoryNotFound() {
        final Category category = new Category();
        category.setId(UUID.randomUUID());
        final Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());

        when(categoryService.getCategoryById(category.getId())).thenThrow(CategoryNotFoundException.class);
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        assertThrows(CategoryNotFoundException.class, () ->
                transactionService.setTransactionCategory(transaction.getId(), category.getId()));
    }

    @Test
    public void setTransactionCategoryThrowExceptionWhenTransactionNotFound() {
        final Category category = new Category();
        category.setId(UUID.randomUUID());
        final Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());

        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.empty());

        assertThrows(TransactionNotFoundException.class, () ->
                transactionService.setTransactionCategory(transaction.getId(), category.getId()));
    }

    @Test
    public void transactionRepositorySaveTransactionWhenCategoryAndTransactionFound() {
        final Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());

        transactionService.saveTransaction(transaction);

        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    @Test
    public void setTransactionCategorySaveTransactionWhenCategoryAndTransactionFound() {
        final Category category = new Category();
        category.setId(UUID.randomUUID());
        final Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());

        when(categoryService.getCategoryById(category.getId())).thenReturn(category);
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        transactionService.setTransactionCategory(transaction.getId(), category.getId());

        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }
}