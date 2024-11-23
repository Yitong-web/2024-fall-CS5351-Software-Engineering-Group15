package com.taobao.arthas.core.util;

import java.math.BigDecimal;

public class MethodAnalysis {
    private String methodName;
    private String className;
    private BigDecimal timeCost;

    // Constructor
    public MethodAnalysis() {}

    public MethodAnalysis(String methodName, String className, BigDecimal timeCost) {
        this.methodName = methodName;
        this.className = className;
        this.timeCost = timeCost;
    }

    // Getters
    public String getMethodName() {
        return methodName;
    }

    public String getClassName() {
        return className;
    }

    public BigDecimal getTimeCost() {
        return timeCost;
    }

    // Setters
    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void setTimeCost(BigDecimal timeCost) {
        this.timeCost = timeCost;
    }
}
