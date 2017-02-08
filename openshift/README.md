# Hawkular Services OpenShift Template

```bash
ansible-playbook playbook.yaml
```

It asks for the root password because it is necessary for flushing the ip tables. If you are not comfortable with running it as a root (and too lazy to check we are not doing anything bad :), you may want to run:

```bash
ansible-playbook playbook.yaml --extra-vars "flush_ip_tables=false"
```

and run the `sudo iptables -F` on your own.

## Prerequisites
```
sudo dnf install -y ansible origin-clients
```
