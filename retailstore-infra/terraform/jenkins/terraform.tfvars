aws_region     = "us-east-1"
aws_account_id = "123456789012"       # replace with your AWS account ID

# Replace with your actual VPC and subnet IDs
# Find them: AWS Console → VPC → Your VPCs / Subnets
# OR: aws ec2 describe-vpcs --query 'Vpcs[].VpcId'
vpc_id    = "vpc-xxxxxxxxxxxxxxxxx"
subnet_id = "subnet-xxxxxxxxxxxxxxxxx"  # must be a PUBLIC subnet (has route to IGW)

# Must already exist in AWS EC2 → Key Pairs
# Create one: aws ec2 create-key-pair --key-name retailstore-jenkins --query 'KeyMaterial' --output text > ~/.ssh/retailstore-jenkins.pem
key_pair_name = "retailstore-jenkins"

instance_type    = "t3.medium"
allowed_ssh_cidr = "0.0.0.0/0"   # replace with your IP: "x.x.x.x/32"
