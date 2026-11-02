package com.anontokyo.lottery.infrastructure.repository;

import org.junit.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 使用 Spring 的真实事务生命周期，验证已回滚与提交结果未知不会混用同一回补策略。 */
public class StockReservationTransactionsTest {
    @Test
    public void confirmedRollbackRestoresStockAndPreservesTheSqlFailure() {
        ProbeManager manager = new ProbeManager();
        Runnable recovery = mock(Runnable.class);
        IllegalStateException sqlFailure = new IllegalStateException("SQL 写入失败");
        RuntimeException actual = assertThrows(RuntimeException.class, () ->
                StockReservationTransactions.execute(new TransactionTemplate(manager), status -> {
                    throw sqlFailure;
                }, recovery, "rollback-probe"));
        assertSame(sqlFailure, actual);
        verify(recovery).run();
    }

    @Test
    public void ambiguousCommitMustNotIncreaseInventory() {
        ProbeManager manager = new ProbeManager();
        manager.commitFailure = new TransactionSystemException("COMMIT 连接中断，结果未知");
        Runnable recovery = mock(Runnable.class);
        assertSame(manager.commitFailure, assertThrows(RuntimeException.class, () ->
                StockReservationTransactions.execute(new TransactionTemplate(manager), status -> "written",
                        recovery, "commit-probe")));
        verifyZeroInteractions(recovery);
    }

    @Test
    public void failedRollbackMustNotPretendThatWritesWereUndone() {
        ProbeManager manager = new ProbeManager();
        manager.rollbackFailure = new TransactionSystemException("ROLLBACK 连接中断");
        Runnable recovery = mock(Runnable.class);
        assertSame(manager.rollbackFailure, assertThrows(RuntimeException.class, () ->
                StockReservationTransactions.execute(new TransactionTemplate(manager), status -> {
                    throw new IllegalStateException("SQL 写入失败");
                }, recovery, "rollback-failure-probe")));
        verifyZeroInteractions(recovery);
    }

    @Test
    public void failureBeforeTransactionBeginsCanRestoreTheReservation() {
        ProbeManager manager = new ProbeManager();
        manager.beginFailure = new TransactionSystemException("未获得数据库连接");
        Runnable recovery = mock(Runnable.class);
        assertSame(manager.beginFailure, assertThrows(RuntimeException.class, () ->
                StockReservationTransactions.execute(new TransactionTemplate(manager), status -> "unreachable",
                        recovery, "begin-probe")));
        verify(recovery).run();
    }

    @Test
    public void recoveryFailureIsAttachedWithoutReplacingTheSqlRootCause() {
        IllegalStateException sqlFailure = new IllegalStateException("SQL 写入失败");
        IllegalStateException recoveryFailure = new IllegalStateException("Redis 回补失败");
        Runnable recovery = mock(Runnable.class);
        doThrow(recoveryFailure).when(recovery).run();
        assertSame(sqlFailure, assertThrows(RuntimeException.class, () ->
                StockReservationTransactions.execute(new TransactionTemplate(new ProbeManager()), status -> {
                    throw sqlFailure;
                }, recovery, "double-failure-probe")));
        assertArrayEquals(new Throwable[]{recoveryFailure}, sqlFailure.getSuppressed());
    }

    @Test
    public void explicitBusinessRollbackIsCompensatedByTheExistingCallerOnly() {
        Runnable recovery = mock(Runnable.class);
        String code = StockReservationTransactions.execute(new TransactionTemplate(new ProbeManager()), status -> {
            status.setRollbackOnly();
            return "NO_UPDATE";
        }, recovery, "business-rejection-probe");
        assertEquals("NO_UPDATE", code);
        verifyZeroInteractions(recovery);
    }

    /** 仅在事务管理器的真实 begin/commit/rollback 边界注入失败，不伪造 afterCompletion 结果。 */
    private static class ProbeManager extends AbstractPlatformTransactionManager {
        private RuntimeException beginFailure;
        private RuntimeException commitFailure;
        private RuntimeException rollbackFailure;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (beginFailure != null) throw beginFailure;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (commitFailure != null) throw commitFailure;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            if (rollbackFailure != null) throw rollbackFailure;
        }
    }
}
