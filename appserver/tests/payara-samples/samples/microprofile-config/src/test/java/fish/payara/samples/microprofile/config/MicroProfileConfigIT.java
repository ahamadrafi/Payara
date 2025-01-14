/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2022 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.samples.microprofile.config;

import fish.payara.samples.PayaraArquillianTestRunner;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URL;

@RunWith(PayaraArquillianTestRunner.class)
public class MicroProfileConfigIT {

    @ArquillianResource
    private URL url;

    @Deployment(testable = false)
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "microprofile-config.war")
                .addClass(TestApplication.class)
                .addClass(TestResource.class)
                .addAsManifestResource(
                        TestApplication.class.getResource("/META-INF/microprofile-config.properties"),
                        "microprofile-config.properties");
    }

    @Test
    public void testNonExistentConfigValue() throws IOException {
        WebTarget webtarget = ClientBuilder.newClient().target(url + "api/test/getNonExistent");
        Response response = webtarget.request().get();
        Assert.assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        Assert.assertTrue(response.readEntity(String.class).contains("java.util.NoSuchElementException: Unable to find property with name fish.payara.samples.microprofile.config.nonexistent"));
    }

    @Test
    public void testNonExistentOptionalConfigValue() throws Exception {
        WebTarget webtarget = ClientBuilder.newClient().target(url + "api/test/getNonExistentOptional");
        Response response = webtarget.request().get();

        Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        Assert.assertTrue(response.readEntity(String.class).equals("Config value not found!"));
    }

    @Test
    public void testPartialNonExistentConfigValue() {
        WebTarget webtarget = ClientBuilder.newClient().target(url + "api/test/getPartialNonExistent");
        Response response = webtarget.request().get();
        Assert.assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        Assert.assertTrue(response.readEntity(String.class).contains("java.util.NoSuchElementException: Unable to resolve expression ${NONEXISTENT}partial"));
    }
}
