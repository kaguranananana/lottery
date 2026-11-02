package com.anontokyo.lottery.infrastructure.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 依据真实事务完成状态处理预扣回补；不把提交结果未知的异常当成已回滚。 */
final class StockReservationTransactions {
    private static final Logger logger = LoggerFactory.getLogger(StockReservationTransactions.class);
    private static final int NOT_STARTED = -1;

    private StockReservationTransactions() {
    }

    static <T> T execute(TransactionTemplate transaction, TransactionCallback<T> action,
            Runnable recover, String reservation) {
        Completion completion = new Completion();
        try {
            return transaction.execute(status -> {
                // 进入事务后先记录未知，防止 SQL 或提交故障被误判为未开始。
                completion.status = TransactionSynchronization.STATUS_UNKNOWN;
                // Spring 在实际提交/回滚完成后提供结果，不能仅凭异常类型推测。
                TransactionSynchronizationManager.registerSynchronization(completion);
                return action.doInTransaction(status);
            });
        } catch (RuntimeException failure) {
            if (completion.status == NOT_STARTED
                    || completion.status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                // 未执行订单 SQL 或已确认回滚时，才允许归还本次 Redis 预扣。
                try {
                    recover.run();
                } catch (RuntimeException recoveryFailure) {
                    // 保留原 SQL/事务异常，同时附上回补故障，避免覆盖根因。
                    failure.addSuppressed(recoveryFailure);
                    logger.error("stock reservation recovery failed, reservation:{}", reservation, recoveryFailure);
                }
            } else {
                // 提交结果未知时不能加库存；记录关联标识，按持久事实对账。
                logger.error("stock reservation transaction outcome uncertain, reconciliation required, reservation:{} status:{}",
                        reservation, completion.status, failure);
            }
            throw failure;
        }
    }

    private static final class Completion extends TransactionSynchronizationAdapter {
        private int status = NOT_STARTED;

        @Override
        public void afterCompletion(int status) {
            // 只观察事务结果；回补在 execute 的异常出口执行，保证错误可见。
            this.status = status;
        }
    }
}
