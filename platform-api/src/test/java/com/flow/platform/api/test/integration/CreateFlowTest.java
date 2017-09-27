/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flow.platform.api.test.integration;

import com.flow.platform.api.domain.credential.RSACredentialDetail;
import com.flow.platform.api.domain.node.Flow;
import com.flow.platform.api.domain.node.Node;
import com.flow.platform.api.domain.node.Yml;
import com.flow.platform.api.domain.envs.FlowEnvs;
import com.flow.platform.api.domain.envs.FlowEnvs.YmlStatusValue;
import com.flow.platform.api.domain.envs.GitEnvs;
import com.flow.platform.api.service.CredentialService;
import com.flow.platform.api.service.node.NodeService;
import com.flow.platform.api.service.node.YmlService;
import com.flow.platform.api.test.TestBase;
import com.flow.platform.util.ObjectWrapper;
import com.flow.platform.util.git.model.GitSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.FileSystemUtils;

/**
 * @author yang
 */
public class CreateFlowTest extends TestBase {

    private final static String GIT_SSH_URL = "git@github.com:flow-ci-plugin/for-testing.git";

    private final static String GIT_HTTP_URL = "https://github.com/flow-ci-plugin/for-testing.git";

    @Autowired
    private NodeService nodeService;

    @Autowired
    private YmlService ymlService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private Path workspace;

    @Value(value = "${domain}")
    private String domain;

    private Flow flow;

    @Before
    public void before() {
        flow = nodeService.createEmptyFlow("/flow-integration");
        setFlowToReady(flow);
        Assert.assertNotNull(nodeService.find(flow.getPath()));
    }

    @Test
    public void should_create_flow_and_init_yml_via_ssh() throws Throwable {
        // setup git related env
        Map<String, String> env = new HashMap<>();
        env.put(GitEnvs.FLOW_GIT_SOURCE.name(), GitSource.UNDEFINED_SSH.name());
        env.put(GitEnvs.FLOW_GIT_URL.name(), GIT_SSH_URL);
        env.put(GitEnvs.FLOW_GIT_SSH_PRIVATE_KEY.name(), getResourceContent("ssh_private_key"));
        nodeService.setFlowEnv(flow.getPath(), env);

        Flow loaded = (Flow) nodeService.find(flow.getPath());
        Assert.assertNotNull(loaded);
        Assert.assertEquals(GitSource.UNDEFINED_SSH.name(), loaded.getEnv(GitEnvs.FLOW_GIT_SOURCE));
        Assert.assertEquals(GIT_SSH_URL, loaded.getEnv(GitEnvs.FLOW_GIT_URL));
        Assert.assertEquals(FlowEnvs.YmlStatusValue.NOT_FOUND.value(), loaded.getEnv(FlowEnvs.FLOW_YML_STATUS));

        // async to clone and return .flow.yml content
        final String loadedYml = loadYml(flow);

        loaded = nodeService.findFlow(flow.getPath());
        Assert.assertEquals(FlowEnvs.YmlStatusValue.FOUND.value(), loaded.getEnv(FlowEnvs.FLOW_YML_STATUS));

        Yml ymlStorage = ymlDao.get(loaded.getPath());
        Assert.assertNotNull(ymlStorage);
        Assert.assertEquals(loadedYml, ymlStorage.getFile());
    }

    @Test
    public void should_create_flow_with_credential_and_init_yml_via_ssh() throws Throwable {
        // given: RSA credential
        final String rsaCredentialName = "flow-deploy-key";
        final String privateKey = getResourceContent("ssh_private_key");
        final String publicKey = getResourceContent("ssh_public_key");

        RSACredentialDetail rsaDetail = new RSACredentialDetail(publicKey, privateKey);
        credentialService.createOrUpdate(rsaCredentialName, rsaDetail);

        // when: set flow env with credential and load yml content from git
        Map<String, String> env = new HashMap<>();
        env.put(GitEnvs.FLOW_GIT_SOURCE.name(), GitSource.UNDEFINED_SSH.name());
        env.put(GitEnvs.FLOW_GIT_URL.name(), GIT_SSH_URL);
        env.put(GitEnvs.FLOW_GIT_CREDENTIAL.name(), rsaCredentialName);
        nodeService.setFlowEnv(flow.getPath(), env);

        // async to clone and return .flow.yml content
        final String loadedYml = loadYml(flow);

        // then: verify yml is loaded
        Flow loaded = nodeService.findFlow(flow.getPath());
        Assert.assertEquals(FlowEnvs.YmlStatusValue.FOUND.value(), loaded.getEnv(FlowEnvs.FLOW_YML_STATUS));

        Yml ymlStorage = ymlDao.get(loaded.getPath());
        Assert.assertNotNull(ymlStorage);
        Assert.assertEquals(loadedYml, ymlStorage.getFile());
    }

    @Test
    public void should_has_yml_error_message_when_ssh_key_invalid() throws Throwable {
        // setup git related env
        Map<String, String> env = new HashMap<>();
        env.put(GitEnvs.FLOW_GIT_SOURCE.name(), GitSource.UNDEFINED_SSH.name());
        env.put(GitEnvs.FLOW_GIT_URL.name(), GIT_SSH_URL);
        env.put(GitEnvs.FLOW_GIT_SSH_PRIVATE_KEY.name(), "invalid ssh key xxxx");
        nodeService.setFlowEnv(flow.getPath(), env);

        // async to clone and return .flow.yml content
        loadYml(flow);

        Node loadedFlow = nodeService.find(flow.getPath());
        Assert.assertEquals(YmlStatusValue.ERROR.value(), loadedFlow.getEnv(FlowEnvs.FLOW_YML_STATUS));
        Assert.assertNotNull(loadedFlow.getEnv(FlowEnvs.FLOW_YML_ERROR_MSG));
    }

    @Test
    public void should_create_flow_and_init_yml_via_http() throws Throwable {
        // setup git related env
        Map<String, String> env = new HashMap<>();
        env.put(GitEnvs.FLOW_GIT_SOURCE.name(), GitSource.UNDEFINED_HTTP.name());
        env.put(GitEnvs.FLOW_GIT_URL.name(), GIT_HTTP_URL);
        env.put(GitEnvs.FLOW_GIT_HTTP_USER.name(), "");
        env.put(GitEnvs.FLOW_GIT_HTTP_PASS.name(), "");
        nodeService.setFlowEnv(flow.getPath(), env);

        // async to clone and return .flow.yml content
        final String loadedYml = loadYml(flow);

        Flow loaded = nodeService.findFlow(flow.getPath());
        Assert.assertEquals(FlowEnvs.YmlStatusValue.FOUND.value(), loaded.getEnv(FlowEnvs.FLOW_YML_STATUS));

        Yml ymlStorage = ymlDao.get(loaded.getPath());
        Assert.assertNotNull(ymlStorage);
        Assert.assertEquals(loadedYml, ymlStorage.getFile());
    }

    @After
    public void after() {
        FileSystemUtils.deleteRecursively(Paths.get(workspace.toString(), "flow-integration").toFile());
    }

    private String loadYml(Node node) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final ObjectWrapper<String> ymlWrapper = new ObjectWrapper<>();

        ymlService.loadYmlContent(node.getPath(), ymlStorage -> {
            ymlWrapper.setInstance(ymlStorage.getFile());
            latch.countDown();
        });

        latch.await(60, TimeUnit.SECONDS);
        return ymlWrapper.getInstance();
    }
}
