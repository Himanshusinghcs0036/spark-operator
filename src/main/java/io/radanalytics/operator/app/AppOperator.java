package io.radanalytics.operator.app;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.radanalytics.operator.common.AbstractOperator;
import io.radanalytics.operator.common.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Operator(forKind = "app", prefix = "radanalytics.io", infoClass = AppInfo.class)
public class AppOperator extends AbstractOperator<AppInfo> {

    private static final Logger log = LoggerFactory.getLogger(AbstractOperator.class.getName());
    private KubernetesAppDeployer deployer = new KubernetesAppDeployer(entityName, prefix);

    public AppOperator() {
    }

    protected void onAdd(AppInfo app) {
        KubernetesResourceList list = deployer.getResourceList(app, namespace);
        client.resourceList(list).createOrReplace();
    }

    protected void onDelete(AppInfo app) {
        String name = app.getName();
        client.services().withLabels(deployer.getLabelsForDeletion(name)).delete();
        client.replicationControllers().withLabels(deployer.getLabelsForDeletion(name)).delete();
        client.pods().withLabels(deployer.getLabelsForDeletion(name)).delete();
    }
}
