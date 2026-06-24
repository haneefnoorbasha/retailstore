output "jenkins_url" {
  description = "Jenkins UI URL — use this for GitHub webhook and browser access"
  value       = "http://${aws_eip.jenkins.public_ip}:${var.jenkins_port}"
}

output "jenkins_public_ip" {
  description = "Elastic IP — paste this into the GitHub webhook URL"
  value       = aws_eip.jenkins.public_ip
}

output "ssh_command" {
  description = "SSH into Jenkins EC2 for troubleshooting"
  value       = "ssh -i ~/.ssh/${var.key_pair_name}.pem ec2-user@${aws_eip.jenkins.public_ip}"
}

output "initial_admin_password_command" {
  description = "Run this via SSH to get the initial Jenkins admin password"
  value       = "sudo cat /var/jenkins_home/secrets/initialAdminPassword"
}
