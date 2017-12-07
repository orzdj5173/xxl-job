package com.xxl.job.admin.core.trigger;

import com.xxl.job.admin.controller.JobInfoController;
import com.xxl.job.admin.core.enums.ExecutorFailStrategyEnum;
import com.xxl.job.admin.core.jobbean.RemoteHttpJobBean;
import com.xxl.job.admin.core.model.XxlJobGroup;
import com.xxl.job.admin.core.model.XxlJobInfo;
import com.xxl.job.admin.core.model.XxlJobLog;
import com.xxl.job.admin.core.route.ExecutorRouteStrategyEnum;
import com.xxl.job.admin.core.schedule.XxlJobDynamicScheduler;
import com.xxl.job.admin.core.thread.JobFailMonitorHelper;
import com.xxl.job.admin.core.thread.JobRegistryMonitorHelper;
import com.xxl.job.admin.service.impl.XxlJobServiceImpl;
import com.xxl.job.core.biz.ExecutorBiz;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.biz.model.TriggerParam;
import com.xxl.job.core.enums.ExecutorBlockStrategyEnum;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;

/**
 * xxl-job trigger
 * Created by xuxueli on 17/7/13.
 */
public class XxlJobTrigger {
    private static Logger logger = LoggerFactory.getLogger(XxlJobTrigger.class);

    /**
     * trigger job
     *
     * @param jobId
     */
    /**
     * todo 一个job真正的执行逻辑在这里,jobId 由jobkey得到, jobkey由jobGroup和jobName决定，根据quertz的约定，jobGroup和jobName标识一个唯一job
     *
     * @see RemoteHttpJobBean#executeInternal(org.quartz.JobExecutionContext)
     * @see org.quartz.JobKey#JobKey(java.lang.String, java.lang.String)
     * @param jobId
     */
    public static void trigger(int jobId) {

        // load data
        /**
         * todo 取出jobInfo, jobInfo是在添加job的时候保存到数据库的，jobinfgo里保存了一个任务执行的全部信息
         * @see XxlJobServiceImpl#add(com.xxl.job.admin.core.model.XxlJobInfo)
         */
        XxlJobInfo jobInfo = XxlJobDynamicScheduler.xxlJobInfoDao.loadById(jobId);              // job info
        if (jobInfo == null) {
            logger.warn(">>>>>>>>>>>> xxl-job trigger fail, jobId invalid，jobId={}", jobId);
            return;
        }
        /**
         * todo 拿到一个job的所有执行器地址列表, 在页面上通过调用JobInfoController#add 添加一个执行器后，通过JobRegistryMonitorHelper开启一个线程,每30秒扫描一次所有注册服务器,添加到对应的执行器地址列表中
         * @see JobInfoController#add(com.xxl.job.admin.core.model.XxlJobInfo)
         * @see JobRegistryMonitorHelper#start()
         */
        XxlJobGroup group = XxlJobDynamicScheduler.xxlJobGroupDao.load(jobInfo.getJobGroup());  // group info

        /**
         * todo 阻塞处理策略
         */
        ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(jobInfo.getExecutorBlockStrategy(), ExecutorBlockStrategyEnum.SERIAL_EXECUTION);  // block strategy
        /**
         * todo 失败处理策略
         * @see ExecutorFailStrategyEnum
         */
        ExecutorFailStrategyEnum failStrategy = ExecutorFailStrategyEnum.match(jobInfo.getExecutorFailStrategy(), ExecutorFailStrategyEnum.FAIL_ALARM);    // fail strategy
        /**
         * todo 路由策略
         * @see ExecutorRouteStrategyEnum  这里定义了几个路由策咯，路由的时候会用到 routeRun 方法
         * @see com.xxl.job.admin.core.route.ExecutorRouter#routeRun(com.xxl.job.core.biz.model.TriggerParam, java.util.ArrayList)
         */
        ExecutorRouteStrategyEnum executorRouteStrategyEnum = ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null);    // route strategy
        ArrayList<String> addressList = (ArrayList<String>) group.getRegistryList();

        // broadcast
        if (ExecutorRouteStrategyEnum.SHARDING_BROADCAST == executorRouteStrategyEnum && CollectionUtils.isNotEmpty(addressList)) {
            /**
             * todo 分片广播
             */
            for (int i = 0; i < addressList.size(); i++) {
                String address = addressList.get(i);

                // 1、save log-id
                XxlJobLog jobLog = new XxlJobLog();
                jobLog.setJobGroup(jobInfo.getJobGroup());
                jobLog.setJobId(jobInfo.getId());
                XxlJobDynamicScheduler.xxlJobLogDao.save(jobLog);
                logger.debug(">>>>>>>>>>> xxl-job trigger start, jobId:{}", jobLog.getId());

                // 2、prepare trigger-info
                //jobLog.setExecutorAddress(executorAddress);
                jobLog.setGlueType(jobInfo.getGlueType());
                jobLog.setExecutorHandler(jobInfo.getExecutorHandler());
                jobLog.setExecutorParam(jobInfo.getExecutorParam());
                jobLog.setTriggerTime(new Date());

                ReturnT<String> triggerResult = new ReturnT<String>(null);
                StringBuffer triggerMsgSb = new StringBuffer();
                triggerMsgSb.append("注册方式：").append( (group.getAddressType() == 0)?"自动注册":"手动录入" );
                triggerMsgSb.append("<br>阻塞处理策略：").append(blockStrategy.getTitle());
                triggerMsgSb.append("<br>失败处理策略：").append(failStrategy.getTitle());
                triggerMsgSb.append("<br>地址列表：").append(group.getRegistryList());
                triggerMsgSb.append("<br>路由策略：").append(executorRouteStrategyEnum.getTitle()).append("("+i+"/"+addressList.size()+")"); // update01

                // 3、trigger-valid
                if (triggerResult.getCode()==ReturnT.SUCCESS_CODE && CollectionUtils.isEmpty(addressList)) {
                    triggerResult.setCode(ReturnT.FAIL_CODE);
                    triggerMsgSb.append("<br>----------------------<br>").append("调度失败：").append("执行器地址为空");
                }

                if (triggerResult.getCode() == ReturnT.SUCCESS_CODE) {
                    // 4.1、trigger-param
                    TriggerParam triggerParam = new TriggerParam();
                    triggerParam.setJobId(jobInfo.getId());
                    triggerParam.setExecutorHandler(jobInfo.getExecutorHandler());
                    triggerParam.setExecutorParams(jobInfo.getExecutorParam());
                    triggerParam.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
                    triggerParam.setLogId(jobLog.getId());
                    triggerParam.setLogDateTim(jobLog.getTriggerTime().getTime());
                    triggerParam.setGlueType(jobInfo.getGlueType());
                    triggerParam.setGlueSource(jobInfo.getGlueSource());
                    triggerParam.setGlueUpdatetime(jobInfo.getGlueUpdatetime().getTime());
                    triggerParam.setBroadcastIndex(i);
                    triggerParam.setBroadcastTotal(addressList.size()); // update02

                    // 4.2、trigger-run (route run / trigger remote executor)
                    /**
                     * todo 调用执行方法
                     */
                    triggerResult = runExecutor(triggerParam, address);     // update03
                    triggerMsgSb.append("<br><br><span style=\"color:#00c0ef;\" > >>>>>>>>>>>触发调度<<<<<<<<<<< </span><br>").append(triggerResult.getMsg());

                    // 4.3、trigger (fail retry)
                    if (triggerResult.getCode()!=ReturnT.SUCCESS_CODE && failStrategy == ExecutorFailStrategyEnum.FAIL_RETRY) {
                        triggerResult = runExecutor(triggerParam, address);  // update04
                        triggerMsgSb.append("<br><br><span style=\"color:#F39C12;\" > >>>>>>>>>>>失败重试<<<<<<<<<<< </span><br>").append(triggerResult.getMsg());
                    }
                }

                // 5、save trigger-info
                jobLog.setExecutorAddress(triggerResult.getContent());
                jobLog.setTriggerCode(triggerResult.getCode());
                jobLog.setTriggerMsg(triggerMsgSb.toString());
                XxlJobDynamicScheduler.xxlJobLogDao.updateTriggerInfo(jobLog);

                // 6、monitor triger
                JobFailMonitorHelper.monitor(jobLog.getId());
                logger.debug(">>>>>>>>>>> xxl-job trigger end, jobId:{}", jobLog.getId());

            }
            return;
        }

        // 1、save log-id
        XxlJobLog jobLog = new XxlJobLog();
        jobLog.setJobGroup(jobInfo.getJobGroup());
        jobLog.setJobId(jobInfo.getId());
        XxlJobDynamicScheduler.xxlJobLogDao.save(jobLog);
        logger.debug(">>>>>>>>>>> xxl-job trigger start, jobId:{}", jobLog.getId());

        // 2、prepare trigger-info
        //jobLog.setExecutorAddress(executorAddress);
        jobLog.setGlueType(jobInfo.getGlueType());
        jobLog.setExecutorHandler(jobInfo.getExecutorHandler());
        jobLog.setExecutorParam(jobInfo.getExecutorParam());
        jobLog.setTriggerTime(new Date());

        ReturnT<String> triggerResult = new ReturnT<String>(null);
        StringBuffer triggerMsgSb = new StringBuffer();
        triggerMsgSb.append("注册方式：").append( (group.getAddressType() == 0)?"自动注册":"手动录入" );
        triggerMsgSb.append("<br>阻塞处理策略：").append(blockStrategy.getTitle());
        triggerMsgSb.append("<br>失败处理策略：").append(failStrategy.getTitle());
        triggerMsgSb.append("<br>地址列表：").append(group.getRegistryList());
        triggerMsgSb.append("<br>路由策略：").append(executorRouteStrategyEnum.getTitle());

        // 3、trigger-valid
        if (triggerResult.getCode()==ReturnT.SUCCESS_CODE && CollectionUtils.isEmpty(addressList)) {
            triggerResult.setCode(ReturnT.FAIL_CODE);
            triggerMsgSb.append("<br>----------------------<br>").append("调度失败：").append("执行器地址为空");
        }

        if (triggerResult.getCode() == ReturnT.SUCCESS_CODE) {
            // 4.1、trigger-param
            TriggerParam triggerParam = new TriggerParam();
            triggerParam.setJobId(jobInfo.getId());
            triggerParam.setExecutorHandler(jobInfo.getExecutorHandler());
            triggerParam.setExecutorParams(jobInfo.getExecutorParam());
            triggerParam.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
            triggerParam.setLogId(jobLog.getId());
            triggerParam.setLogDateTim(jobLog.getTriggerTime().getTime());
            triggerParam.setGlueType(jobInfo.getGlueType());
            triggerParam.setGlueSource(jobInfo.getGlueSource());
            triggerParam.setGlueUpdatetime(jobInfo.getGlueUpdatetime().getTime());
            triggerParam.setBroadcastIndex(0);
            triggerParam.setBroadcastTotal(1);

            // 4.2、trigger-run (route run / trigger remote executor)
            /**
             * todo 调用路由的routeRun方法，调用执行器执行任务
             * @see ExecutorRouteStrategyEnum 看里面的几个实现，
             * 里面最终会调用
             * @see com.xxl.job.admin.core.trigger.XxlJobTrigger#runExecutor(com.xxl.job.core.biz.model.TriggerParam, java.lang.String)
             * 传入设置好的triggerParam 和 执行器地址
             */
            triggerResult = executorRouteStrategyEnum.getRouter().routeRun(triggerParam, addressList);
            triggerMsgSb.append("<br><br><span style=\"color:#00c0ef;\" > >>>>>>>>>>>触发调度<<<<<<<<<<< </span><br>").append(triggerResult.getMsg());

            // 4.3、trigger (fail retry)
            if (triggerResult.getCode()!=ReturnT.SUCCESS_CODE && failStrategy == ExecutorFailStrategyEnum.FAIL_RETRY) {
                triggerResult = executorRouteStrategyEnum.getRouter().routeRun(triggerParam, addressList);
                triggerMsgSb.append("<br><br><span style=\"color:#F39C12;\" > >>>>>>>>>>>失败重试<<<<<<<<<<< </span><br>").append(triggerResult.getMsg());
            }
        }

        // 5、save trigger-info
        jobLog.setExecutorAddress(triggerResult.getContent());
        jobLog.setTriggerCode(triggerResult.getCode());
        jobLog.setTriggerMsg(triggerMsgSb.toString());
        XxlJobDynamicScheduler.xxlJobLogDao.updateTriggerInfo(jobLog);

        // 6、monitor triger
        JobFailMonitorHelper.monitor(jobLog.getId());
        logger.debug(">>>>>>>>>>> xxl-job trigger end, jobId:{}", jobLog.getId());
    }

    /**
     * run executor
     * @param triggerParam
     * @param address
     * @return  ReturnT.content: final address
     */
    /**
     * todo 这里调用执行器执行任务
     * @param triggerParam
     * @param address
     * @return
     */

    public static ReturnT<String> runExecutor(TriggerParam triggerParam, String address){
        ReturnT<String> runResult = null;
        try {
            /**
             * todo 获取一个可以发送任务的 ExecutorBiz, 使用了代理，调用run方法的时机逻辑见
             * @see com.xxl.job.core.rpc.netcom.NetComClientProxy#getObject()
             */
            ExecutorBiz executorBiz = XxlJobDynamicScheduler.getExecutorBiz(address);
            runResult = executorBiz.run(triggerParam);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            runResult = new ReturnT<String>(ReturnT.FAIL_CODE, ""+e );
        }

        StringBuffer runResultSB = new StringBuffer("触发调度：");
        runResultSB.append("<br>address：").append(address);
        runResultSB.append("<br>code：").append(runResult.getCode());
        runResultSB.append("<br>msg：").append(runResult.getMsg());

        runResult.setMsg(runResultSB.toString());
        runResult.setContent(address);
        return runResult;
    }

}
