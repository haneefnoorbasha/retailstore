#!/bin/bash
set -euo pipefail

# ── Mount Jenkins home EBS volume ────────────────────────────────────────────
# Wait for the volume to attach
while [ ! -b /dev/xvdf ]; do sleep 2; done

# Format only if not already formatted (safe on re-deploys)
if ! blkid /dev/xvdf; then
  mkfs.ext4 /dev/xvdf
fi

mkdir -p /var/jenkins_home
mount /dev/xvdf /var/jenkins_home
echo "/dev/xvdf /var/jenkins_home ext4 defaults,nofail 0 2" >> /etc/fstab

# ── Install Java 21 (Jenkins requirement) ────────────────────────────────────
dnf install -y java-21-amazon-corretto

# ── Install Jenkins LTS ───────────────────────────────────────────────────────
wget -O /etc/yum.repos.d/jenkins.repo \
    https://pkg.jenkins.io/redhat-stable/jenkins.repo
rpm --import https://pkg.jenkins.io/redhat-stable/jenkins.io-2023.key
dnf install -y jenkins

# Point Jenkins home to the EBS volume
mkdir -p /var/jenkins_home
chown jenkins:jenkins /var/jenkins_home
sed -i 's|JENKINS_HOME=.*|JENKINS_HOME=/var/jenkins_home|' /etc/sysconfig/jenkins 2>/dev/null || \
  echo 'JENKINS_HOME=/var/jenkins_home' >> /etc/sysconfig/jenkins

# ── Install Docker (Jenkins needs it to run docker build) ────────────────────
dnf install -y docker
systemctl enable --now docker
usermod -aG docker jenkins   # Jenkins process can run docker commands

# ── Install AWS CLI v2 ───────────────────────────────────────────────────────
# No credentials needed — EC2 IAM Role provides access automatically
curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
/tmp/aws/install
rm -rf /tmp/awscliv2.zip /tmp/aws

# ── Install Maven 3.9.9 ──────────────────────────────────────────────────────
MAVEN_VERSION=3.9.9
curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    -o /tmp/maven.tar.gz
tar xzf /tmp/maven.tar.gz -C /opt
ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven
echo 'export PATH="/opt/maven/bin:$PATH"' > /etc/profile.d/maven.sh
rm /tmp/maven.tar.gz

# ── Start Jenkins ─────────────────────────────────────────────────────────────
systemctl enable --now jenkins

echo "Jenkins bootstrapped. Access at http://$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4):8080"
echo "Initial admin password: cat /var/jenkins_home/secrets/initialAdminPassword"
