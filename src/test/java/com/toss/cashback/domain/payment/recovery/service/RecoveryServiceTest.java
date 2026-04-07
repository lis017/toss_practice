package com.toss.cashback.domain.payment.recovery.service;

import com.toss.cashback.domain.payment.entity.PaymentStatus;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryAttempt;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryResult;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryTriggerType;
import com.toss.cashback.domain.payment.recovery.repository.RecoveryAttemptRepository;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.domain.payment.service.PaymentService;
import com.toss.cashback.domain.settlement.repository.SettlementRepository;
import com.toss.cashback.domain.settlement.service.SettlementService;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// ======= [15번] RecoveryService 단위 테스트 =======
/**
 * =====================================================================
 * RecoveryService 단위 테스트 (Mockito)
 * =====================================================================
 *
 * 검증 시나리오:
 * 1. 자동 복구 성공 → SUCCESS RecoveryAttempt 저장
 * 2. 자동 복구 실패 → FAILED RecoveryAttempt 저장 + 예외 전파
 * 3. 이미 복구된 건 재복구 요청 → no-op + SUCCESS 저장 (멱등성)
 * 4. 수동 복구 성공 → MANUAL SUCCESS RecoveryAttempt 저장
 * 5. 정산 레코드 이미 존재 → 재생성 스킵 후 상태 복구
 * =====================================================================
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecoveryService 단위 테스트")
class RecoveryServiceTest {

    @Mock
    private PaymentTransactionRepository transactionRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementService settlementService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private RecoveryAttemptRepository recoveryAttemptRepository;

    @InjectMocks
    private RecoveryService recoveryService;

    // ================================================================
    // 시나리오 1: 자동 복구 성공
    // ================================================================

    @Test
    @DisplayName("자동 복구 성공 → SUCCESS RecoveryAttempt 저장, 정산 레코드 재생성 + 상태 복구")
    void recover_auto_success_savesSuccessAttempt() {
        PaymentTransaction tx = buildPostProcessFailedTx(1L, 1L, 100L, 2L, 100_000L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));
        when(settlementRepository.findByPaymentTransactionId(1L)).thenReturn(List.of()); // 정산 레코드 없음
        when(recoveryAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recoveryService.recover(1L, RecoveryTriggerType.AUTO);

        // 정산 레코드 재생성 호출
        verify(settlementService).createSettlementRecord(1L, 100L, 2L, 100_000L);

        // 상태 복구 호출
        verify(paymentService).markTransactionPendingSettlement(1L);

        // RecoveryAttempt SUCCESS 저장
        ArgumentCaptor<RecoveryAttempt> captor = ArgumentCaptor.forClass(RecoveryAttempt.class);
        verify(recoveryAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(RecoveryResult.SUCCESS);
        assertThat(captor.getValue().getTriggerType()).isEqualTo(RecoveryTriggerType.AUTO);
    }

    // ================================================================
    // 시나리오 2: 복구 실패 → FAILED RecoveryAttempt 저장
    // ================================================================

    @Test
    @DisplayName("복구 실패 → FAILED RecoveryAttempt 저장 + 예외 전파")
    void recover_auto_failure_savesFailedAttemptAndThrows() {
        PaymentTransaction tx = buildPostProcessFailedTx(1L, 1L, 100L, 2L, 100_000L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));
        when(settlementRepository.findByPaymentTransactionId(1L)).thenReturn(List.of());
        doThrow(new RuntimeException("정산 DB 연결 실패"))
                .when(settlementService).createSettlementRecord(anyLong(), anyLong(), anyLong(), anyLong());
        when(recoveryAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> recoveryService.recover(1L, RecoveryTriggerType.AUTO))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);

        // 실패해도 RecoveryAttempt FAILED 저장 확인
        ArgumentCaptor<RecoveryAttempt> captor = ArgumentCaptor.forClass(RecoveryAttempt.class);
        verify(recoveryAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(RecoveryResult.FAILED);
        assertThat(captor.getValue().getFailureReason()).contains("정산 DB 연결 실패");
    }

    // ================================================================
    // 시나리오 3: 이미 복구된 건 재복구 → no-op (멱등성)
    // ================================================================

    @Test
    @DisplayName("이미 복구된 건(PENDING_SETTLEMENT) 재복구 요청 → no-op + SUCCESS 저장 (멱등성)")
    void recover_alreadyRecovered_noOpWithSuccessAttempt() {
        PaymentTransaction tx = mock(PaymentTransaction.class);
        when(tx.getStatus()).thenReturn(PaymentStatus.PENDING_SETTLEMENT);  // 이미 복구됨
        // tx.getId()는 no-op 경로에서 호출 안 됨 → 불필요 stub 제거
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));

        recoveryService.recover(1L, RecoveryTriggerType.MANUAL);

        // 정산 레코드 재생성/상태 변경 없음
        verify(settlementService, never()).createSettlementRecord(anyLong(), anyLong(), anyLong(), anyLong());
        verify(paymentService, never()).markTransactionPendingSettlement(anyLong());

        // no-op도 SUCCESS 기록
        ArgumentCaptor<RecoveryAttempt> captor = ArgumentCaptor.forClass(RecoveryAttempt.class);
        verify(recoveryAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(RecoveryResult.SUCCESS);
        assertThat(captor.getValue().getTriggerType()).isEqualTo(RecoveryTriggerType.MANUAL);
    }

    // ================================================================
    // 시나리오 4: 정산 레코드 이미 존재 → 재생성 스킵 후 상태만 복구
    // ================================================================

    @Test
    @DisplayName("정산 레코드 이미 존재 → 재생성 스킵, markTransactionPendingSettlement만 호출")
    void recover_settlementAlreadyExists_skipCreateAndOnlyUpdateStatus() {
        PaymentTransaction tx = buildPostProcessFailedTx(1L, 1L, 100L, 2L, 100_000L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));

        // 정산 레코드 이미 존재
        when(settlementRepository.findByPaymentTransactionId(1L))
                .thenReturn(List.of(mock(com.toss.cashback.domain.settlement.entity.SettlementRecord.class)));
        when(recoveryAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        recoveryService.recover(1L, RecoveryTriggerType.AUTO);

        // [핵심] 정산 레코드 재생성 없음 (이미 존재)
        verify(settlementService, never()).createSettlementRecord(anyLong(), anyLong(), anyLong(), anyLong());

        // 상태 복구는 진행
        verify(paymentService).markTransactionPendingSettlement(1L);
    }

    // ================================================================
    // 헬퍼 메서드
    // ================================================================

    private PaymentTransaction buildPostProcessFailedTx(Long id, Long paymentTxId,
                                                          Long virtualAccountId, Long merchantAccountId,
                                                          Long amount) {
        PaymentTransaction tx = mock(PaymentTransaction.class);
        when(tx.getId()).thenReturn(id);
        when(tx.getStatus()).thenReturn(PaymentStatus.POST_PROCESS_FAILED);
        // 일부 테스트에서 사용 안 될 수 있음 → lenient로 설정 (UnnecessaryStubbingException 방지)
        lenient().when(tx.getVirtualAccountId()).thenReturn(virtualAccountId);
        lenient().when(tx.getToAccountId()).thenReturn(merchantAccountId);
        lenient().when(tx.getAmount()).thenReturn(amount);
        return tx;
    }
}
