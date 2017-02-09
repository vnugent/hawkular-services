# Hawkular Services OpenShift Template

```bash
ansible-playbook playbook.yaml
```

It asks for the root password because it is necessary for flushing the ip tables. If you are not comfortable with running it as a root (and too lazy to check we are not doing anything bad :), you may want to run:

```bash
ansible-playbook playbook.yaml --extra-vars "flush_ip_tables=false"
```

and run the `sudo iptables -F` on your own.

This runs the Hawkular Services and create two persistent volumes, so that you do not lose your data when restarting openshift. However, it uses the `hostPath` strategy so that the pod uses the fs entry on the node it is scheduled on. For more advanced scenario, we suggest using NFS persistent volumes or some cloud storage PVs.

If you want to start Hawkular Services and you don't care about your data, use:
```bash
./startEphemeral.sh
```

## Prerequisites
The ansible and openshift packages should be installed.

```
sudo dnf install -y ansible origin-clients
```

If the ansible fails with following error:
```
Ensure that the Docker daemon is running with the following argument: --insecure-registry 172.30.0.0/16
```
... just add the insecure registry entry to the docker deamon configuration and restart the docker:

Edit `/etc/sysconfig/docker` or `/etc/docker/daemon.json` depending on your docker and run `sudo systemctl daemon-reload && sudo systemctl restart docker`.
