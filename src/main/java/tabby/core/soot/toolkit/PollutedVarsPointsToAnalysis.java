package tabby.core.soot.toolkit;

import lombok.Getter;
import lombok.Setter;
import soot.*;
import soot.jimple.ArrayRef;
import soot.jimple.InstanceFieldRef;
import soot.toolkits.graph.DirectedGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;
import tabby.core.data.Context;
import tabby.core.data.DataContainer;
import tabby.core.data.TabbyVariable;
import tabby.core.soot.switcher.stmt.SimpleStmtSwitcher;
import tabby.core.soot.switcher.stmt.StmtSwitcher;
import tabby.core.soot.switcher.value.SimpleLeftValueSwitcher;
import tabby.core.soot.switcher.value.SimpleRightValueSwitcher;
import tabby.db.bean.ref.MethodReference;

import java.util.*;

/**
 * May alias analysis
 * 主要工作：维护一个当前Unit之前以及之后的变量状态，提供给可控变量分析
 * @author wh1t3P1g
 * @since 2020/11/24
 */
@Setter
@Getter
public class PollutedVarsPointsToAnalysis extends ForwardFlowAnalysis<Unit, Map<Local, TabbyVariable>> {

    private Context context; // 同一函数内共享的上下文内容
    private DataContainer dataContainer;
    private Map<Local, TabbyVariable> emptyMap;
    private Map<Local, TabbyVariable> initialMap;
    private StmtSwitcher stmtSwitcher;
    private MethodReference methodRef;
    private Body body;
    /**
     * Construct the analysis from a DirectedGraph representation of a Body.
     *
     * @param graph
     */
    public PollutedVarsPointsToAnalysis(DirectedGraph<Unit> graph) {
        super(graph);
        emptyMap = new HashMap<>();
        initialMap = new HashMap<>();

    }

    public void doAnalysis(){
        for(ValueBox box:body.getUseAndDefBoxes()){
            Value value = box.getValue();
            Type type = value.getType();
            if(type instanceof PrimType){ // 对于基础数据类型 直接跳过
                continue;
            }
            if(value instanceof Local && !initialMap.containsKey(value)){
                initialMap.put((Local) value, TabbyVariable.makeLocalInstance((Local) value));
            }else if(value instanceof InstanceFieldRef){
                InstanceFieldRef ifr = (InstanceFieldRef) value;
                SootField sootField = ifr.getField();
                Value base = ifr.getBase();
                if(base instanceof Local){
                    TabbyVariable baseVar = initialMap.get(base);
                    if(baseVar == null){
                        baseVar = TabbyVariable.makeLocalInstance((Local) base);
                        initialMap.put((Local) base, baseVar);
                    }
                    TabbyVariable fieldVar = baseVar.getField(sootField.getSignature());
                    if(fieldVar == null){
                        fieldVar = TabbyVariable.makeFieldInstance(baseVar, sootField);
                        fieldVar.setOrigin(value);
                        baseVar.addField(sootField.getSignature(), fieldVar);
                    }
                }
            }else if(value instanceof ArrayRef){
                ArrayRef v = (ArrayRef) value;
                Value base = v.getBase();
                if(base instanceof Local){
                    TabbyVariable baseVar = initialMap.get(base);
                    if(baseVar == null){
                        baseVar = TabbyVariable.makeLocalInstance((Local) base);
                        initialMap.put((Local) base, baseVar);
                    }
                }
            }
        }
        super.doAnalysis();
    }

    @Override
    protected void flowThrough(Map<Local, TabbyVariable> in, Unit d, Map<Local, TabbyVariable> out) {
        Map<Local, TabbyVariable> newIn = new HashMap<>();
        copy(in, newIn);
        context.setLocalMap(newIn);
        context.setInitialMap(initialMap);
        stmtSwitcher.setContext(context);
        stmtSwitcher.setDataContainer(dataContainer);
        d.apply(stmtSwitcher);
        out.putAll(context.getLocalMap());

    }

    @Override
    protected Map<Local, TabbyVariable> newInitialFlow() {
        return new HashMap<>(emptyMap);
    }


    @Override
    protected void merge(Map<Local, TabbyVariable> in1, Map<Local, TabbyVariable> in2, Map<Local, TabbyVariable> out) {
        // if else while 分支汇聚的时候 对结果集进行处理 交集
        out.clear();

        in2.forEach((local, in2Var) -> {// 取交集
            TabbyVariable in1Var = in1.get(local);
            if(in1Var != null){
                boolean in1VarPolluted = in1Var.containsPollutedVar(new ArrayList<>());
                boolean in2VarPolluted = in2Var.containsPollutedVar(new ArrayList<>());
                if(in1VarPolluted){
                    // 1. in1可控 in2不可控
                    // 2. in1可控 in2可控 优先选择in1
                    out.put(local, in1Var.deepClone(new ArrayList<>()));
                }else if(in2VarPolluted){
                    // 3. in1不可控 in2可控
                    out.put(local, in2Var.deepClone(new ArrayList<>()));
                }else{
                    // 4. in1 in2 不可控 优先选择in1
                    out.put(local, in1Var.deepClone(new ArrayList<>()));
                }
            }
            // 存在什么问题？
            // 对于两者皆可控的情况，我们舍弃其中一方都会导致后续路径污点的丢失
        });
    }

    @Override
    protected void copy(Map<Local, TabbyVariable> source, Map<Local, TabbyVariable> dest) {
        dest.clear();
        for (Map.Entry<Local, TabbyVariable> entry : source.entrySet()) {
            Local value = entry.getKey();
            TabbyVariable variable = entry.getValue();
            dest.put(value, variable.deepClone(new ArrayList<>()));
        }
    }

    public static PollutedVarsPointsToAnalysis makeDefault(MethodReference methodRef,
                                                           Body body,
                                                           DirectedGraph<Unit> graph,
                                                           DataContainer dataContainer,
                                                           Context context, boolean reset){
        PollutedVarsPointsToAnalysis analysis = new PollutedVarsPointsToAnalysis(graph);
        // 配置switchers
        StmtSwitcher switcher = new SimpleStmtSwitcher();
        SimpleLeftValueSwitcher leftSwitcher = new SimpleLeftValueSwitcher();
        leftSwitcher.setReset(reset);
        switcher.setReset(reset);
        switcher.setMethodRef(methodRef);
        switcher.setLeftValueSwitcher(leftSwitcher);
        switcher.setRightValueSwitcher(new SimpleRightValueSwitcher());
        // 配置pta依赖
        analysis.setBody(body);
        analysis.setDataContainer(dataContainer);
        analysis.setStmtSwitcher(switcher);
        analysis.setContext(context);
        analysis.setMethodRef(methodRef);
        // 进行分析
        analysis.doAnalysis();
        return analysis;
    }
}