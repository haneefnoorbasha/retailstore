variable "cluster_id" {
  description = "ElastiCache replication group ID"
  type        = string
}

variable "node_type" {
  description = "ElastiCache node type (e.g. cache.t3.micro, cache.r6g.large)"
  type        = string
}

variable "num_cache_clusters" {
  description = "Number of cache clusters (1 = no replica, 2 = primary + 1 replica)"
  type        = number
  default     = 1
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "subnet_ids" {
  description = "Private subnet IDs for the ElastiCache subnet group"
  type        = list(string)
}

variable "eks_cluster_security_group_id" {
  description = "EKS cluster security group ID — Redis allows ingress from this SG"
  type        = string
}

variable "tags" {
  description = "Tags applied to all resources"
  type        = map(string)
  default     = {}
}
