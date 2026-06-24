# ── Latest Amazon Linux 2023 AMI ─────────────────────────────────────────────
data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

# ── IAM Role for Jenkins EC2 ──────────────────────────────────────────────────
# EC2 carries the role — no static AWS credentials needed inside Jenkins
resource "aws_iam_role" "jenkins" {
  name = "retailstore-jenkins-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Project   = "retailstore"
    ManagedBy = "terraform"
  }
}

resource "aws_iam_role_policy" "jenkins_ecr" {
  name = "jenkins-ecr-push"
  role = aws_iam_role.jenkins.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:CompleteLayerUpload",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer"
        ]
        Resource = "arn:aws:ecr:${var.aws_region}:${var.aws_account_id}:repository/retailstore/*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "jenkins" {
  name = "retailstore-jenkins-profile"
  role = aws_iam_role.jenkins.name
}

# ── Security Group ────────────────────────────────────────────────────────────
resource "aws_security_group" "jenkins" {
  name        = "retailstore-jenkins-sg"
  description = "Jenkins CI server"
  vpc_id      = var.vpc_id

  # Jenkins UI — open to all (GitHub webhook needs to reach this)
  ingress {
    description = "Jenkins UI"
    from_port   = var.jenkins_port
    to_port     = var.jenkins_port
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # JNLP agent port — for build agents connecting back to controller
  ingress {
    description = "Jenkins JNLP agents"
    from_port   = 50000
    to_port     = 50000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # SSH — restrict to your IP in production
  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "retailstore-jenkins-sg"
    ManagedBy = "terraform"
  }
}

# ── EBS Volume for Jenkins home (separate from root — survives EC2 replacement) ─
resource "aws_ebs_volume" "jenkins_home" {
  availability_zone = data.aws_availability_zone.current.name
  size              = 30    # GB — stores all jobs, build history, plugins
  type              = "gp3"
  encrypted         = true

  tags = {
    Name      = "retailstore-jenkins-home"
    ManagedBy = "terraform"
  }
}

data "aws_availability_zone" "current" {
  name = "${var.aws_region}a"
}

# ── EC2 Instance ──────────────────────────────────────────────────────────────
resource "aws_instance" "jenkins" {
  ami                    = data.aws_ami.amazon_linux_2023.id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  key_name               = var.key_pair_name
  iam_instance_profile   = aws_iam_instance_profile.jenkins.name
  vpc_security_group_ids = [aws_security_group.jenkins.id]

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    encrypted   = true
  }

  user_data = file("${path.module}/scripts/user_data.sh")

  tags = {
    Name      = "retailstore-jenkins"
    ManagedBy = "terraform"
  }
}

resource "aws_volume_attachment" "jenkins_home" {
  device_name = "/dev/xvdf"
  volume_id   = aws_ebs_volume.jenkins_home.id
  instance_id = aws_instance.jenkins.id
}

# ── Elastic IP — stable URL for GitHub webhook ────────────────────────────────
resource "aws_eip" "jenkins" {
  instance = aws_instance.jenkins.id
  domain   = "vpc"

  tags = {
    Name      = "retailstore-jenkins-eip"
    ManagedBy = "terraform"
  }
}
