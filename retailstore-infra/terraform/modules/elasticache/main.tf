# ── Subnet Group ─────────────────────────────────────────────────────────────

resource "aws_elasticache_subnet_group" "this" {
  name        = "${var.cluster_id}-subnet-group"
  subnet_ids  = var.subnet_ids
  description = "Subnet group for ${var.cluster_id} Redis cluster"

  tags = merge(var.tags, {
    Name = "${var.cluster_id}-subnet-group"
  })
}

# ── Security Group ────────────────────────────────────────────────────────────

resource "aws_security_group" "this" {
  name        = "${var.cluster_id}-sg"
  description = "Allow Redis from EKS cluster"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from EKS nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.eks_cluster_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.cluster_id}-sg"
  })
}

# ── ElastiCache Replication Group ─────────────────────────────────────────────

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.cluster_id
  description          = "Redis cluster for ${var.cluster_id}"

  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.node_type
  num_cache_clusters   = var.num_cache_clusters
  parameter_group_name = "default.redis7"
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true

  automatic_failover_enabled = var.num_cache_clusters > 1

  snapshot_retention_limit = 1
  snapshot_window          = "03:00-04:00"

  tags = var.tags
}
