package ax.ha.clouddevelopment;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroupProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.InstanceTarget;
import software.amazon.awscdk.services.iam.IManagedPolicy;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.DatabaseInstanceProps;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;

public class EC2DockerApplicationStack extends Stack {

    // Do not remove these variables. The hosted zone can be used later when creating DNS records
    private final IHostedZone hostedZone = HostedZone.fromHostedZoneAttributes(this, "HaHostedZone", HostedZoneAttributes.builder()
            .hostedZoneId("Z0413857YT73A0A8FRFF")
            .zoneName("cloud-ha.com")
            .build());

    // Do not remove, you can use this when defining what VPC your security group, instance and load balancer should be part of.
    private final IVpc vpc = Vpc.fromLookup(this, "MyVpc", VpcLookupOptions.builder()
            .isDefault(true)
            .build());
    public EC2DockerApplicationStack(final Construct scope, final String id, final StackProps props, final String groupName) {
        super(scope, id, props);
        // TODO: Define your cloud resources here.

        // Creating a secret resource
        Secret databaseSecret = Secret.Builder.create(this, "databaseSecret")
                .secretName("postgresCredentials")
                .description("Another test using SecretZ Man4ger")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"username\":\"master\"}") // Okay, defining the structure of the JSON
                        .generateStringKey("password") // Here it recognizes that it should generate a string for the key password
                        .excludeCharacters("/@\" ") // Need to exclude characters to match the password template
                        .build())
                .build();

        final SecurityGroup ec2SecurityGroup = SecurityGroup.Builder.create(this, "ec2SecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        final SecurityGroup databaseSecurityGroup = SecurityGroup.Builder.create(this, "databaseSecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        databaseSecurityGroup.addIngressRule(ec2SecurityGroup, Port.tcp(5432), "Allow postgres access to EC2");

        // Defining the policies here for readability and i think it makes it easier to change in the future
        final List<IManagedPolicy> managedPolicies = Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly"));

        final Role role = Role.Builder.create(this, "EC2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(managedPolicies)
                .build();

        // Allowing the EC2 instance to read the secret so it
        // can access the database using the password tied to the secret
        databaseSecret.grantRead(role);

        final ApplicationLoadBalancer loadBalancer = new ApplicationLoadBalancer(this, "applicationLoadBalancer",
                ApplicationLoadBalancerProps.builder()
                        .vpc(vpc)
                        .vpcSubnets(SubnetSelection.builder()
                                .subnetType(SubnetType.PUBLIC)
                                .build())
                        .internetFacing(true)
                        .build());

        ApplicationListener listener = loadBalancer.addListener("listener", BaseApplicationListenerProps.builder()
                .port(80)
                .protocol(ApplicationProtocol.HTTP)
                .open(true)
                .build());

        new RecordSet(this, "loadBalancer", RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(loadBalancer)))
                .zone(hostedZone)
                .recordName(groupName + "-api.cloud-ha.com")
                .build());

        loadBalancer.getConnections().allowTo(ec2SecurityGroup, Port.tcp(80));

        String postgresUser = "master";

        DatabaseInstance rds = new DatabaseInstance(this, "RDS-database", DatabaseInstanceProps.builder()
                .engine(DatabaseInstanceEngine.POSTGRES)
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .credentials(Credentials.fromSecret(databaseSecret))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .removalPolicy(RemovalPolicy.DESTROY)
                .securityGroups(List.of(databaseSecurityGroup))
                .allocatedStorage(5)
                .build());

        rds.getConnections().allowFrom(ec2SecurityGroup, Port.tcp(5432));
        String databaseUrl = rds.getDbInstanceEndpointAddress();

        UserData userData = UserData.forLinux();
        userData.addCommands(
                "yum install -y docker jq",
                "sudo systemctl start docker",
                "aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin 292370674225.dkr.ecr.eu-north-1.amazonaws.com",
                "DB_PASSWORD=$(aws secretsmanager get-secret-value --secret-id postgresCredentials --query SecretString --output text --region eu-north-1 | jq -r .password)",
                "docker run -d -e DB_URL=" + databaseUrl + " -e DB_USERNAME=" + postgresUser + " -e DB_PASSWORD=$DB_PASSWORD -e SPRING_PROFILES_ACTIVE=postgres --name my-application -p 80:8080 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest"
        );
        // Launch Template for my autoscaling group
        LaunchTemplate launchTemplate = new LaunchTemplate(this, "template", LaunchTemplateProps.builder()
                .machineImage(AmazonLinuxImage.Builder.create()
                        .generation(AmazonLinuxGeneration.AMAZON_LINUX_2)
                        .cpuType(AmazonLinuxCpuType.X86_64)
                        .build())
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .securityGroup(ec2SecurityGroup)
                .userData(userData)
                .role(role)
                .build());

        // Trying to create an autoscaling group
        AutoScalingGroup autoScalingGroup = new AutoScalingGroup(this, "autoScalingGroup",
                AutoScalingGroupProps.builder()
                        .vpc(vpc)
                        .launchTemplate(launchTemplate)
                        .vpcSubnets(SubnetSelection.builder()
                                .subnetType(SubnetType.PUBLIC)
                                .build())
                        .desiredCapacity(2)
                        .minCapacity(2)
                        .maxCapacity(5)
                        .build());

        listener.addTargets("targetGroup", AddApplicationTargetsProps.builder()
                .port(80)
                .targets(List.of(autoScalingGroup)) // Instead of my EC2 instance the autoscaling group should be the target
                .build());

        new CfnOutput(this, "database-endpoint", CfnOutputProps.builder()
                .value(databaseUrl)
                .description("Database Endpoint: ")
                .build());
    }
}