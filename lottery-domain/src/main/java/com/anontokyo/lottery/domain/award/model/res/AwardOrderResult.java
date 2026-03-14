package com.anontokyo.lottery.domain.award.model.res;

import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;

/**
 * 奖品订单落单结果。
 */
public class AwardOrderResult {

    private String code;
    private String info;
    private InvoiceVO invoiceVO;
    private AwardStockSettlementVO awardStockSettlementVO;

    public AwardOrderResult() {
    }

    public AwardOrderResult(String code, String info) {
        this.code = code;
        this.info = info;
    }

    public AwardOrderResult(String code, String info, InvoiceVO invoiceVO) {
        this.code = code;
        this.info = info;
        this.invoiceVO = invoiceVO;
    }

    public AwardOrderResult(String code, String info, InvoiceVO invoiceVO,
            AwardStockSettlementVO awardStockSettlementVO) {
        this.code = code;
        this.info = info;
        this.invoiceVO = invoiceVO;
        this.awardStockSettlementVO = awardStockSettlementVO;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public InvoiceVO getInvoiceVO() {
        return invoiceVO;
    }

    public void setInvoiceVO(InvoiceVO invoiceVO) {
        this.invoiceVO = invoiceVO;
    }

    public AwardStockSettlementVO getAwardStockSettlementVO() {
        return awardStockSettlementVO;
    }

    public void setAwardStockSettlementVO(AwardStockSettlementVO awardStockSettlementVO) {
        this.awardStockSettlementVO = awardStockSettlementVO;
    }
}
