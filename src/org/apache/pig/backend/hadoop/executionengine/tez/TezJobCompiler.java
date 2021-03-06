/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.tez;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.pig.PigConfiguration;
import org.apache.pig.PigException;
import org.apache.pig.backend.hadoop.PigATSClient;
import org.apache.pig.backend.hadoop.executionengine.JobCreationException;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.TezOperPlan;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.TezPlanContainer;
import org.apache.pig.backend.hadoop.executionengine.tez.plan.TezPlanContainerNode;
import org.apache.pig.impl.PigContext;
import org.apache.pig.tools.pigstats.tez.TezScriptState;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.TezConfiguration;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * This is compiler class that takes a TezOperPlan and converts it into a
 * JobControl object with the relevant dependency info maintained. The
 * JobControl object is made up of TezJobs each of which has a JobConf.
 */
public class TezJobCompiler {
    private static final Log log = LogFactory.getLog(TezJobCompiler.class);

    private PigContext pigContext;
    private Configuration conf;
    private boolean disableDAGRecovery;

    public TezJobCompiler(PigContext pigContext, Configuration conf) throws IOException {
        this.pigContext = pigContext;
        this.conf = conf;
    }

    public DAG buildDAG(TezPlanContainerNode tezPlanNode, Map<String, LocalResource> localResources)
            throws IOException, YarnException {
        DAG tezDag = DAG.create(tezPlanNode.getOperatorKey().toString());
        tezDag.setCredentials(tezPlanNode.getTezOperPlan().getCredentials());
        TezDagBuilder dagBuilder = new TezDagBuilder(pigContext, tezPlanNode.getTezOperPlan(), tezDag, localResources);
        dagBuilder.visit();
        dagBuilder.avoidContainerReuseIfInputSplitInDisk();
        disableDAGRecovery = dagBuilder.shouldDisableDAGRecovery();
        return tezDag;
    }

    public TezJob compile(TezPlanContainerNode tezPlanNode, TezPlanContainer planContainer)
            throws JobCreationException {
        TezJob job = null;
        try {
            // A single Tez job always pack only 1 Tez plan. We will track
            // Tez job asynchronously to exploit parallel execution opportunities.
            job = getJob(tezPlanNode, planContainer);
        } catch (JobCreationException jce) {
            throw jce;
        } catch(Exception e) {
            int errCode = 2017;
            String msg = "Internal error creating job configuration.";
            throw new JobCreationException(msg, errCode, PigException.BUG, e);
        }

        return job;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private TezJob getJob(TezPlanContainerNode tezPlanNode, TezPlanContainer planContainer)
            throws JobCreationException {
        try {
            Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();
            localResources.putAll(planContainer.getLocalResources());
            TezOperPlan tezPlan = tezPlanNode.getTezOperPlan();
            localResources.putAll(tezPlan.getExtraResources());
            String shipFiles = pigContext.getProperties().getProperty("pig.streaming.ship.files");
            if (shipFiles != null) {
                for (String file : shipFiles.split(",")) {
                    TezResourceManager.getInstance().addTezResource(new File(file.trim()).toURI());
                }
            }
            String cacheFiles = pigContext.getProperties().getProperty("pig.streaming.cache.files");
            if (cacheFiles != null) {
                addCacheResources(cacheFiles.split(","));
            }
            for (Map.Entry<String, LocalResource> entry : localResources.entrySet()) {
                log.info("Local resource: " + entry.getKey());
            }

            // Print Tez plan before launching if needed
            if (conf.getBoolean(PigConfiguration.PIG_PRINT_EXEC_PLAN, false)) {
                log.info(tezPlanNode.getTezOperPlan());
            }

            DAG tezDag = buildDAG(tezPlanNode, localResources);
            tezDag.setDAGInfo(createDagInfo(TezScriptState.get().getScript()));
            // set Tez caller context
            // Reflection for the following code since it is only available since tez 0.8.1:
            // CallerContext context = CallerContext.create(ATSService.CallerContext, ATSService.getPigAuditId(pigContext),
            //     ATSService.EntityType, "");
            // tezDag.setCallerContext(context);
            Class callerContextClass = null;
            try {
                callerContextClass = Class.forName("org.apache.tez.client.CallerContext");
            } catch (ClassNotFoundException e) {
                // If pre-Tez 0.8.1, skip setting CallerContext
            }
            if (callerContextClass != null) {
                Method builderBuildMethod = callerContextClass.getMethod("create", String.class,
                        String.class, String.class, String.class);
                Object context = builderBuildMethod.invoke(null, PigATSClient.CALLER_CONTEXT,
                        PigATSClient.getPigAuditId(pigContext), PigATSClient.ENTITY_TYPE, "");
                Method dagSetCallerContext = tezDag.getClass().getMethod("setCallerContext",
                        context.getClass());
                dagSetCallerContext.invoke(tezDag, context);
            }
            log.info("Total estimated parallelism is " + tezPlan.getEstimatedTotalParallelism());
            TezConfiguration tezConf = new TezConfiguration(conf);
            if (disableDAGRecovery
                    && tezConf.getBoolean(TezConfiguration.DAG_RECOVERY_ENABLED,
                            TezConfiguration.DAG_RECOVERY_ENABLED_DEFAULT)) {
                tezConf.setBoolean(TezConfiguration.DAG_RECOVERY_ENABLED, false);
            }
            return new TezJob(tezConf, tezDag, localResources, tezPlan);
        } catch (Exception e) {
            int errCode = 2017;
            String msg = "Internal error creating job configuration.";
            throw new JobCreationException(msg, errCode, PigException.BUG, e);
        }
    }

    private void addCacheResources(String[] fileNames) throws Exception {
        for (String fileName : fileNames) {
            fileName = fileName.trim();
            if (fileName.length() > 0) {
                URI resourceURI = new URI(fileName);
                String fragment = resourceURI.getFragment();

                Path remoteFsPath = new Path(resourceURI);
                String resourceName = (fragment != null && fragment.length() > 0) ? fragment : remoteFsPath.getName();
                TezResourceManager.getInstance().addTezResource(resourceName, remoteFsPath);
            }
        }
    }

    private String createDagInfo(String script) throws IOException {
        String dagInfo;
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("context", "Pig");
            jsonObject.put("description", script);
            dagInfo = jsonObject.toString();
        } catch (JSONException e) {
            throw new IOException("Error when trying to convert Pig script to JSON", e);
        }
        log.debug("DagInfo: " + dagInfo);
        return dagInfo;
    }
}

