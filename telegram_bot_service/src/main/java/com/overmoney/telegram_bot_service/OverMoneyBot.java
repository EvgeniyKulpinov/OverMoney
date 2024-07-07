package com.overmoney.telegram_bot_service;

import com.overmoney.telegram_bot_service.constants.Command;
import com.overmoney.telegram_bot_service.constants.InlineKeyboardCallback;
import com.overmoney.telegram_bot_service.exception.VoiceProcessingException;
import com.overmoney.telegram_bot_service.mapper.ChatMemberMapper;
import com.overmoney.telegram_bot_service.mapper.TransactionMapper;
import com.overmoney.telegram_bot_service.model.TelegramMessage;
import com.overmoney.telegram_bot_service.service.*;
import com.overmoney.telegram_bot_service.util.InlineKeyboardMarkupUtil;
import com.override.dto.AccountDataDTO;
import com.override.dto.TransactionDTO;
import com.override.dto.TransactionMessageDTO;
import com.override.dto.TransactionResponseDTO;
import com.override.dto.constants.StatusMailing;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OverMoneyBot extends TelegramLongPollingBot {
    @Value("${bot.name}")
    private String botName;
    @Value("${bot.token}")
    private String botToken;
    @Value("${orchestrator.host}")
    private String orchestratorHost;
    @Autowired
    private OrchestratorRequestService orchestratorRequestService;
    @Autowired
    private TransactionMapper transactionMapper;
    @Autowired
    private VoiceMessageProcessingService voiceMessageProcessingService;
    @Autowired
    private MergeRequestService mergeRequestService;
    @Autowired
    private ChatMemberMapper chatMemberMapper;
    @Autowired
    private InlineKeyboardMarkupUtil inlineKeyboardMarkupUtil;
    @Autowired
    private FileService fileService;
    @Autowired
    private TelegramMessageCheckerService telegramMessageCheckerService;
    @Autowired
    private TelegramMessageService telegramMessageService;
    private final String TRANSACTION_MESSAGE_INVALID = "Мы не смогли распознать ваше сообщение. " +
            "Убедитесь, что сумма и товар указаны верно и попробуйте еще раз :)";
    private final Integer MILLISECONDS_CONVERSION = 1000;
    private final ZoneOffset MOSCOW_OFFSET = ZoneOffset.of("+03:00");
    private final String MERGE_REQUEST_TEXT =
            "Привет, ты добавил меня в груповой чат, теперь я буду отслеживать " +
                    "транзакции всех пользователей в этом чате.\n\n" +
                    "Хочешь перенести данные о своих финансах и использовать их совместно?";
    private final String MERGE_REQUEST_COMPLETED_DEFAULT_TEXT =
            "Удачного совместного использования!";
    private final String MERGE_REQUEST_COMPLETED_TEXT =
            "Данные аккаунта были перенесены.";
    private final String REGISTRATION_INFO_TEXT =
            "Для корректной регистрации аккаунта убедитесь, что на момент добавления в чат бота" +
                    " в чате с ботом только вы. После переноса данных можете добавлять других пользователей";
    private final String INVALID_TRANSACTION_TO_DELETE = "Некорректная транзакция для удаления";
    private final String SUCCESSFUL_DELETION_TRANSACTION = "Эта запись успешно удалена!";
    private final String COMMAND_TO_DELETE_TRANSACTION = "удалить";
    private final String BLANK_MESSAGE = "";
    private final Boolean BOT = true;
    private final String SUCCESSFUL_UPDATE_TRANSACTION_TEXT = "Запись успешно изменена.\n";
    private final String INVALID_UPDATE_TRANSACTION_TEXT = "Некорректная транзакция для изменения.\n" +
            "Возможно, Вы выбрали транзакцию, которая уже была изменена или сообщение бота";

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    @SneakyThrows
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message receivedMessage = update.getMessage();
            Long chatId = receivedMessage.getChatId();
            if (receivedMessage.getLeftChatMember() != null) {
                User remoteUser = receivedMessage.getLeftChatMember();
                if (!remoteUser.getIsBot()) {
                    String backupFileName = fileService.createBackupFileToRemoteInChatUser(chatId, remoteUser.getId());
                    sendBuckUpFile(remoteUser.getId().toString(), backupFileName);
                    orchestratorRequestService.removeChatMemberFromAccount(chatMemberMapper.mapUserToChatMemberDTO(chatId, remoteUser));
                }
            }
            if (!receivedMessage.getNewChatMembers().isEmpty()) {
                List<User> newUsers = receivedMessage.getNewChatMembers();
                HashMap<Boolean, User> usersTypes = getUsersTypes(newUsers);

                if (usersTypes.containsKey(BOT)) {
                    sendRegistrationGroupAccountInfo(chatId);
                    sendMergeRequest(chatId);
                } else {
                    orchestratorRequestService.addNewChatMembersToAccount(
                            newUsers.stream()
                                    .map(member -> chatMemberMapper.mapUserToChatMemberDTO(chatId, member))
                                    .collect(Collectors.toList()));
                }
            }
            botAnswer(receivedMessage);
        }
        if (update.hasMyChatMember()) {
            String status = update.getMyChatMember().getNewChatMember().getStatus();
            if (status.equals("left") && !update.hasMessage()) {
                Long chatId = update.getMyChatMember().getChat().getId();
                Long userId = update.getMyChatMember().getFrom().getId();
                String backUpFileName = fileService.createBackupFileToRemoteInChatUser(chatId, userId);
                sendBuckUpFile(userId.toString(), backUpFileName);
            }
        }
        if (update.hasCallbackQuery()) {
            CallbackQuery callbackQuery = update.getCallbackQuery();
            Long chatId = callbackQuery.getMessage().getChatId();
            Long userId = callbackQuery.getFrom().getId();
            Integer messageToDeleteId = mergeRequestService.getMergeRequestByChatId(chatId).getMessageId();

            if (callbackQuery.getData().equals(InlineKeyboardCallback.MERGE_CATEGORIES.getData())) {
                orchestratorRequestService.registerGroupAccountAndMergeWithCategoriesAndWithoutTransactions(
                        new AccountDataDTO(chatId, userId));
                sendMessage(chatId, MERGE_REQUEST_COMPLETED_TEXT);
            } else if (callbackQuery.getData().equals(InlineKeyboardCallback.MERGE_CATEGORIES_AND_TRANSACTIONS.getData())) {
                orchestratorRequestService.registerGroupAccountAndWithCategoriesAndTransactions(
                        new AccountDataDTO(chatId, userId));
                sendMessage(chatId, MERGE_REQUEST_COMPLETED_TEXT);
            } else if (callbackQuery.getData().equals(InlineKeyboardCallback.DEFAULT.getData())) {
                orchestratorRequestService.registerGroupAccount(new AccountDataDTO(chatId, userId));
            }

            mergeRequestService.updateMergeRequestCompletionByChatId(chatId);
            deleteMessageMarkup(messageToDeleteId, chatId);
            sendMessage(chatId, MERGE_REQUEST_COMPLETED_DEFAULT_TEXT);
        }
    }

    private HashMap<Boolean, User> getUsersTypes(List<User> newUsers) {
        HashMap<Boolean, User> userTypes = new HashMap<>();
        newUsers.forEach(user -> {
            if (user.getIsBot()) {
                userTypes.put(BOT, user);
            } else {
                userTypes.put(!BOT, user);
            }
        });
        return userTypes;
    }

    private void sendRegistrationGroupAccountInfo(Long chatId) throws TelegramApiException {
        SendMessage message = new SendMessage(chatId.toString(), REGISTRATION_INFO_TEXT);
        execute(message);
    }

    private void sendMergeRequest(Long chatId) throws TelegramApiException {
        SendMessage message = new SendMessage(chatId.toString(), MERGE_REQUEST_TEXT);
        message.setReplyMarkup(inlineKeyboardMarkupUtil.generateMergeRequestMarkup());
        Message mergeRequestMessage = execute(message);
        mergeRequestService.saveMergeRequestMessage(mergeRequestMessage);
    }

    private String getReceivedMessage(Message message) {
        String receivedMessageText = BLANK_MESSAGE;
        Long userId = message.getFrom().getId();
        Long chatId = message.getChatId();
        if (message.hasVoice()) {
            log.info("user with id " + userId + " and chatId " + chatId + " sending voice");
            receivedMessageText = voiceMessageProcessingService.processVoiceMessage(message.getVoice(), userId, chatId);
            log.info("recognition result of user with id " + userId + " and chatId " + chatId + " is: " + receivedMessageText);
        } else if (message.hasText()) {
            receivedMessageText = message.getText();
        }
        return receivedMessageText;
    }

    private void botAnswer(Message receivedMessage) {
        Long chatId = receivedMessage.getChatId();
        Long userId = receivedMessage.getFrom().getId();
        Integer messageId = receivedMessage.getMessageId();
        String receivedMessageText = null;
        try {
            receivedMessageText = getReceivedMessage(receivedMessage).toLowerCase();
        } catch (VoiceProcessingException e) {
            log.error("При обработке голосового сообщения произошла: " + e.getMessage(), e);
            String errorMessage = "При обработке голосового сообщения произошла: " + e.getMessage();
            sendMessage(chatId, errorMessage);
            return;
        }
        Message replyToMessage = receivedMessage.getReplyToMessage();
        LocalDateTime date = Instant.ofEpochMilli((long) receivedMessage.getDate() * MILLISECONDS_CONVERSION)
                .atOffset(MOSCOW_OFFSET).toLocalDateTime();
        TransactionMessageDTO transactionMessageDTO = TransactionMessageDTO.builder()
                .message(receivedMessageText)
                .userId(userId)
                .chatId(chatId)
                .date(date)
                .build();
        if (telegramMessageCheckerService.isNonTransactionalMessageMentionedToSomeone(receivedMessageText)
                || receivedMessageText.equals(BLANK_MESSAGE)) {
            return;
        }
        if (replyToMessage != null) {
            TelegramMessage message = telegramMessageService.
                    getTelegramMessageMessageIdAndChatId(replyToMessage.getMessageId(), chatId);
            if (message == null) {
                if (!userId.equals(replyToMessage.getFrom().getId())) {
                    sendMessage(chatId, INVALID_UPDATE_TRANSACTION_TEXT);
                    return;
                }
                processTransaction(chatId, messageId, transactionMessageDTO);
                return;
            }
            if (!receivedMessageText.equals(COMMAND_TO_DELETE_TRANSACTION) &&
                    !receivedMessageText.equalsIgnoreCase(replyToMessage.getText())) {
                UUID idTransaction = message.getIdTransaction();
                TransactionDTO previousTransaction = orchestratorRequestService.getTransactionById(idTransaction);
                transactionMessageDTO.setDate(previousTransaction.getDate());
                updateTransaction(transactionMessageDTO, idTransaction, chatId, messageId);
                return;
            }
        }
        switch (receivedMessageText) {
            case "/start":
                sendMessage(chatId, Command.START.getDescription() + orchestratorHost);
                orchestratorRequestService.registerSingleAccount(new AccountDataDTO(chatId, userId));
                break;
            case "/money":
                sendMessage(chatId, Command.MONEY.getDescription());
                break;
            case "/web":
                sendMessage(chatId, orchestratorHost);
                break;
            case COMMAND_TO_DELETE_TRANSACTION:
                if (replyToMessage != null) {
                    deleteTransaction(replyToMessage, chatId);
                    break;
                }
            default:
                processTransaction(chatId, messageId, transactionMessageDTO);
                break;
        }
    }

    public StatusMailing sendMessage(Long chatId, String messageText) {
        SendMessage message = new SendMessage(chatId.toString(), messageText);
        try {
            execute(message);
            return StatusMailing.SUCCESS;
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
            return StatusMailing.ERROR;
        }
    }

    @SneakyThrows
    private void deleteMessageMarkup(Integer messageId, Long chatId) {
        EditMessageReplyMarkup message = new EditMessageReplyMarkup();
        message.setChatId(chatId.toString());
        message.setMessageId(messageId);
        message.setReplyMarkup(null);
        execute(message);
    }

    public void sendBuckUpFile(String userChatId, String fileName) {
        File file = new File(fileName);
        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(userChatId);
        sendDocument.setDocument(new InputFile(file));
        try {
            execute(sendDocument);
            file.delete();
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
        }
    }

    private void deleteTransaction(Message replyToMessage, Long chatId) {
        try {
            telegramMessageService.deleteTransactionById(replyToMessage.getMessageId(), chatId);
            sendMessage(chatId, SUCCESSFUL_DELETION_TRANSACTION);
        } catch (Exception e) {
            log.error(e.getMessage());
            sendMessage(chatId, INVALID_TRANSACTION_TO_DELETE);
        }
    }

    private void updateTransaction(TransactionMessageDTO transactionMessageDTO, UUID idTransaction, Long chatId, Integer messageId) {
        try {
            TransactionResponseDTO transactionResponseDTO = orchestratorRequestService
                    .submitTransactionForPatch(transactionMessageDTO, idTransaction);
            telegramMessageService.saveTelegramMessage(TelegramMessage.builder()
                    .messageId(messageId)
                    .chatId(chatId)
                    .idTransaction(transactionResponseDTO.getId())
                    .build());
            sendMessage(chatId,
                    SUCCESSFUL_UPDATE_TRANSACTION_TEXT + transactionMapper.mapTransactionResponseToTelegramMessage(transactionResponseDTO));
        } catch (Exception e) {
            log.error(e.getMessage());
            sendMessage(chatId, TRANSACTION_MESSAGE_INVALID);
        }
    }

    private void processTransaction(Long chatId, Integer messageId, TransactionMessageDTO transactionMessageDTO) {
        try {
            TransactionResponseDTO transactionResponseDTO = orchestratorRequestService
                    .sendTransaction(transactionMessageDTO);
            telegramMessageService.saveTelegramMessage(TelegramMessage.builder()
                    .messageId(messageId)
                    .chatId(chatId)
                    .idTransaction(transactionResponseDTO.getId()).build());
            sendMessage(chatId, transactionMapper
                    .mapTransactionResponseToTelegramMessage(transactionResponseDTO));
        } catch (Exception e) {
            log.error(e.getMessage());
            sendMessage(chatId, TRANSACTION_MESSAGE_INVALID);
        }
    }
}