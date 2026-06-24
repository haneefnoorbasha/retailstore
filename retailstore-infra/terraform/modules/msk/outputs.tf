output "cluster_arn" {
  description = "MSK cluster ARN"
  value       = aws_msk_cluster.this.arn
}

output "bootstrap_brokers_tls" {
  description = "TLS bootstrap broker string — use as KAFKA_BOOTSTRAP_SERVERS in Helm values"
  value       = aws_msk_cluster.this.bootstrap_brokers_tls
}

output "cluster_name" {
  description = "MSK cluster name"
  value       = aws_msk_cluster.this.cluster_name
}

output "zookeeper_connect_string" {
  description = "ZooKeeper connection string (not needed for KRaft, kept for tooling compat)"
  value       = aws_msk_cluster.this.zookeeper_connect_string
}
