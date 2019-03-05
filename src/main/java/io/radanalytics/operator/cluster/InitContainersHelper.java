package io.radanalytics.operator.cluster;

import io.fabric8.kubernetes.api.model.*;
import io.radanalytics.types.DownloadDatum;
import io.radanalytics.types.SparkCluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.radanalytics.operator.Constants.getDefaultSparkImage;

public class InitContainersHelper {

    private static final String NEW_CONF_DIR = "conf-new-dir";
    private static final String NEW_CONF_DIR_PATH = "/tmp/config/new";
    private static final String DEFAULT_CONF_DIR_PATH = "/opt/spark/conf";

    /**
     * Based on the SparkCluster configuration, it can add init-containers called 'downloader', 'backup-config' and/or
     * 'override-config'. The firs one will be added if the <code>cluster.getDownloadData()</code> is not empty, while the
     * latter two are always being added together if config map that overrides the default spark configuration exist in the
     * K8s or if the <code>cluster.getSparkConfiguration()</code> is not empty.
     *
     * Optionally adds the init containers that do:
     * <ol>
     *     <li>downloads the data with curl if it's specified</li>
     *     <li>backups the original (default) Spark configuration into <code>NEW_CONF_DIR_PATH</code></li>
     *     <li>copies/replaces the config files in the <code>NEW_CONF_DIR_PATH</code> with the files coming from the config map</li>
     *     <li>overrides (/appends) the key-value entries in the <code>NEW_CONF_DIR_PATH/spark-defaults.conf</code></li>
     *     <li>use the configuration <code>NEW_CONF_DIR_PATH</code> as the configuration for the spark</li>
     * </ol>
     *
     *
     * @param rc ReplicationController instance
     * @param cluster SparkCluster instance
     * @param cmExists whether config map with overrides exists
     * @return modified ReplicationController instance
     */
    public static final ReplicationController addInitContainers(ReplicationController rc,
                                                                 SparkCluster cluster,
                                                                 boolean cmExists) {
        PodSpec podSpec = rc.getSpec().getTemplate().getSpec();

        if (!cluster.getDownloadData().isEmpty()) {
            createDownloader(cluster, podSpec);
        }
        if (cmExists || !cluster.getSparkConfiguration().isEmpty()) {
            createBackupContainer(cluster, podSpec);
            createConfigOverrideContainer(cluster, podSpec, cmExists);
        }

        rc.getSpec().getTemplate().setSpec(podSpec);
        return rc;
    }

    private static Container createDownloader(SparkCluster cluster, PodSpec podSpec) {
        final String mountName = "data-dir";
        final String mountPath = "/tmp/";
        final VolumeMount downloadMount = new VolumeMountBuilder().withName(mountName).withMountPath(mountPath).build();
        final Volume downloadVolume = new VolumeBuilder().withName(mountName).withNewEmptyDir().endEmptyDir().build();
        final List<DownloadDatum> downloadData = cluster.getDownloadData();
        final StringBuilder downloaderCmd = new StringBuilder();
        downloadData.forEach(dl -> {
            String url = dl.getUrl();
            String to = dl.getTo();
            // if 'to' ends with slash, we know it's a directory and we use the -P switch to change the prefix,
            // otherwise using -O for renaming the downloaded file
            String param = to.endsWith("/") ? " -P " : " -O ";
            downloaderCmd.append("wget ");
            downloaderCmd.append(url);
            downloaderCmd.append(param);
            downloaderCmd.append(to);
            downloaderCmd.append(" ; ");
        });

        Container downloader = new ContainerBuilder()
                .withName("downloader")
                .withImage("busybox")
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", "-xc")
                .withArgs(downloaderCmd.toString())
                .withVolumeMounts(downloadMount)
                .build();


        podSpec.getContainers().get(0).getVolumeMounts().add(downloadMount);
        podSpec.getVolumes().add(downloadVolume);
        podSpec.getInitContainers().add(downloader);

        return downloader;
    }

    private static Container createBackupContainer(SparkCluster cluster, PodSpec podSpec) {
        final VolumeMount backupMount = new VolumeMountBuilder().withName(NEW_CONF_DIR).withMountPath(NEW_CONF_DIR_PATH).build();
        final Volume backupVolume = new VolumeBuilder().withName(NEW_CONF_DIR).withNewEmptyDir().endEmptyDir().build();

        Container backup = new ContainerBuilder()
                .withName("backup-config")
                .withImage(Optional.ofNullable(cluster.getCustomImage()).orElse(getDefaultSparkImage()))
                .withCommand("/bin/sh", "-xc")
                .withArgs("cp -r " + DEFAULT_CONF_DIR_PATH + "/* " + NEW_CONF_DIR_PATH)
                .withVolumeMounts(backupMount)
                .build();


        podSpec.getContainers().get(0).getVolumeMounts().add(backupMount);
        podSpec.getVolumes().add(backupVolume);
        podSpec.getInitContainers().add(backup);

        return backup;
    }

    private static Container createConfigOverrideContainer(SparkCluster cluster, PodSpec podSpec, boolean cmExists) {
        final List<io.radanalytics.types.NameValue> config = cluster.getSparkConfiguration();
        String cmMountName = "configmap-dir";
        String cmMountPath = "/tmp/config/fromCM";
        List<VolumeMount> mounts = new ArrayList<>(2);
        if (cmExists) {
            String cmName = getExpectedCMName(cluster);

            final VolumeMount cmMount = new VolumeMountBuilder().withName(cmMountName).withMountPath(cmMountPath).build();
            final Volume cmVolume = new VolumeBuilder().withName(cmMountName).withNewConfigMap().withName(cmName).endConfigMap().build();
            podSpec.getVolumes().add(cmVolume);
            mounts.add(cmMount);
        }

        final VolumeMount backupMount = new VolumeMountBuilder().withName(NEW_CONF_DIR).withMountPath(NEW_CONF_DIR_PATH).build();
        final String origConfMountName = "conf-orig-dir";
        final VolumeMount origConfMount = new VolumeMountBuilder().withName(origConfMountName).withMountPath(DEFAULT_CONF_DIR_PATH).build();
        final Volume origConfVolume = new VolumeBuilder().withName(origConfMountName).withNewEmptyDir().endEmptyDir().build();
        mounts.add(backupMount);
        mounts.add(origConfMount);

        final StringBuilder overrideConfigCmd = new StringBuilder();
        if (cmExists) {
            // add/replace the content of NEW_CONF_DIR_PATH with all the files that comes from the config map
            overrideConfigCmd.append("cp -r ");
            overrideConfigCmd.append(cmMountPath);
            overrideConfigCmd.append("/* ");
            overrideConfigCmd.append(NEW_CONF_DIR_PATH);
            if (!config.isEmpty()) {
                overrideConfigCmd.append(" ; ");
            }
        }

        if (!config.isEmpty()) {
            // override the key-value entries in the spark-defaults.conf
            overrideConfigCmd.append("echo -e \"");
            config.forEach(kv -> {
                overrideConfigCmd.append(kv.getName());
                overrideConfigCmd.append(" ");
                overrideConfigCmd.append(kv.getValue());
                overrideConfigCmd.append("\\n");
            });
            overrideConfigCmd.append("\" >> ");
            overrideConfigCmd.append(NEW_CONF_DIR_PATH);
            overrideConfigCmd.append("/spark-defaults.conf");
        }

        // replace the content of /opt/spark/conf with the newly created config files
        overrideConfigCmd.append(" && cp -r ");
        overrideConfigCmd.append(NEW_CONF_DIR_PATH);
        overrideConfigCmd.append("/* ");
        overrideConfigCmd.append(DEFAULT_CONF_DIR_PATH);

        Container overrideConfig = new ContainerBuilder()
                .withName("override-config")
                .withImage("busybox")
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", "-xc")
                .withArgs(overrideConfigCmd.toString())
                .withVolumeMounts(mounts)
                .build();

        podSpec.getInitContainers().add(overrideConfig);
        podSpec.getContainers().get(0).getVolumeMounts().add(origConfMount);
        podSpec.getVolumes().add(origConfVolume);

        return overrideConfig;
    }

    /**
     * Returns the <code>cluster.getSparkConfigurationMap()</code> if it's there. If not, it defaults to the
     * name of the spark cluster concatenated with <code>'-config'</code> suffix.
     *
     * @param cluster SparkCluster instance
     * @return expected name of the config map
     */
    public static String getExpectedCMName(SparkCluster cluster) {
        return cluster.getSparkConfigurationMap() == null ? cluster.getName() + "-config" : cluster.getSparkConfigurationMap();
    }

    /**
     * Calculates the expected initial delay for the liveness or readiness probe for master or worker
     * it considers these attributes:
     * <ul>
     *  <li>if there is anything to download</li>
     *  <li>if config map with overrides exists</li>
     *  <li>if key-value config entries were passed in the custom resource/CM</li>
     *  <li>cpu limit</li>
     * </ul>
     *
     * Also worker node gets some minor penalisation, because its probe depends on the master's readiness.
     *
     * @param cluster SparkCluster instance
     * @param cmExists if config map with overrides exists
     * @param isMaster whether it is master or worker
     * @return expected time for initial delay for the probes
     */
    public static int getExpectedDelay(SparkCluster cluster, boolean cmExists, boolean isMaster) {
        int delay = 5;
        if (isMaster) {
            if (null != cluster.getMaster() && null != cluster.getMaster().getCpu()) {
                try {
                    double cpu = Double.parseDouble(cluster.getMaster().getCpu());
                    delay += (1.0 / cpu) * 3;
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            }
            delay += 0;
        } else {
            if (null != cluster.getWorker() && null != cluster.getWorker().getCpu()) {
                try {
                    double cpu = Double.parseDouble(cluster.getWorker().getCpu());
                    delay += (1.0 / cpu) * 3;
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            }
            delay += 4;
        }
        delay += cmExists ? 3 : 0;
        delay += !cluster.getSparkConfiguration().isEmpty() ? 3 : 0;
        delay += cluster.getDownloadData().size() * 4;
        return delay;
    }

}
