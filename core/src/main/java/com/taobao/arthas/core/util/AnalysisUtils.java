package com.taobao.arthas.core.util;

import com.taobao.arthas.core.command.monitor200.TraceCommand;
import com.taobao.arthas.core.command.monitor200.TraceEntity;
import com.taobao.arthas.core.command.model.TraceNode;
import com.taobao.arthas.core.command.model.MethodNode;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


public class AnalysisUtils {

    /** @Description: 将最长的n个方法以及优化的方法返回
    * @Param: [args, command, traceEntity]
    * @return: java.util.List<java.lang.String>
    */
    public static List<String> analysis(Integer num , List<String> args,
                                        TraceCommand command, TraceEntity traceEntity) {
        List<String> stringRusult = new ArrayList<>();
        //1、找到所有的叶子节点，并存在一个list里面
        List<MethodAnalysis> methodList = new ArrayList<>();
        buildMethodList(methodList,traceEntity.getModel().getRoot());
        //2、排序一下
        Collections.sort(methodList, new Comparator<MethodAnalysis>() {
            @Override
            public int compare(MethodAnalysis o1, MethodAnalysis o2) {
                return o2.getTimeCost().compareTo(o1.getTimeCost());
            }
        });
        //3、取出最长的n个值 同时取一下方法名和类名
        StringBuilder methodLongestLength = new StringBuilder();
        StringBuilder classString = new StringBuilder(command.getClassPattern());
        StringBuilder methodStrign = new StringBuilder(command.getMethodPattern());
        for(int i = 0 ;i < num; i++){
            MethodAnalysis methodAnalysis = methodList.get(i);
            methodLongestLength.append(methodAnalysis.getMethodName());
            if(i != num-1){
                methodLongestLength.append(" and ");
            }
            classString.append("|").append(methodAnalysis.getClassName());
            methodStrign.append("|").append(methodAnalysis.getMethodName());

        }
        //考虑如何灵活赋值
        Integer classIndex = getArgsClassInxdex(args);
        args.set(classIndex, classString.toString());
        args.set(classIndex+1, methodStrign.toString());

        stringRusult.add(methodLongestLength.toString());
        //4、输出优化的方法
        String firstArg = "";
        if(command.isRegEx()){
            firstArg = "trace ";
        }else {
            firstArg = "trace -E ";
        }
        StringBuilder methodAfterDeal = new StringBuilder(firstArg);
        for(String childString : args){
            //遇到'-L'跳出
            if(childString.equals("-L")){
                break;
            }
            methodAfterDeal.append(childString);
            methodAfterDeal.append(" ");
        }


        stringRusult.add(methodAfterDeal.toString());

        //将每个单独的方法的trace命令也列出来
        for(int i = 0 ;i < num; i++){
            MethodAnalysis methodAnalysis = methodList.get(i);
            String oneTraceMethod = "";
            oneTraceMethod += "if you want just know the time-analysis for "+ methodAnalysis.getClassName()+"."+methodAnalysis.getMethodName();
            oneTraceMethod += " you can use this command: \n"+"trace "+methodAnalysis.getClassName()+" "+methodAnalysis.getMethodName()+" -n 5\n";
            stringRusult.add(oneTraceMethod);
        }

        //调试debug
//        String de = "";
//        de = JSONObject.toJSONString(methodList);
//        stringRusult.add(de);

        return stringRusult;
    }


    private static Integer getArgsClassInxdex(List<String> args) {
        //获取class的index，
        // 如trace -E com.dao.xxxx.controler 为2
        // 如trace com.dao.xxx.contoller 为1
        for(int i = 0 ; i < args.size(); i++){
            //-E
            String arg = args.get(i);
            if(arg.equals("-E")) {
                continue;
            }
            if(arg.equals("--skipJDKMethod")){
                continue;
            }
            if(arg.equals("false")){
                continue;
            }
            if(arg.equals("true")){
                continue;
            }
            return i;
        }
        return 0;
    }

    private static void buildMethodList(List<MethodAnalysis> methodList, TraceNode root) {

        if(root.getChildren() == null){
            MethodNode node = (MethodNode) root;
            MethodAnalysis methodAnalysis = new MethodAnalysis();
            methodAnalysis.setMethodName(node.getMethodName());
            methodAnalysis.setClassName(node.getClassName());
            methodAnalysis.setTimeCost(BigDecimal.valueOf(node.getTotalCost()));
            methodList.add(methodAnalysis);
        }else {

            for(TraceNode node : root.getChildren()){
                buildMethodList(methodList , node);
            }
        }

    }

    private static void getAllJsonString( TraceNode root,String re) {
        String s = JSONObject.toJSONString(root);
        re+=s;
        if(root.getChildren() == null){
            return;
        }
        for(TraceNode node : root.getChildren()){
            getAllJsonString(node,re);
        }

    }


    protected static class MethodAnalysis{
        private String methodName;
        private String className;
        private BigDecimal timeCost;
        
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
}
