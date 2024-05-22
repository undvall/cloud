package ax.ha.clouddevelopment;

import software.amazon.awscdk.*;
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

        final Instance ec2Instance = new Instance(this, "-ec2-assignment2", InstanceProps.builder()
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .instanceName(groupName + "-ec2-assignment2")
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .machineImage(AmazonLinuxImage.Builder.create()
                        .generation(AmazonLinuxGeneration.AMAZON_LINUX_2)
                        .cpuType(AmazonLinuxCpuType.X86_64)
                        .build())
                .securityGroup(ec2SecurityGroup)
                .userDataCausesReplacement(true)
                .role(role)
                .build());


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

        ApplicationTargetGroup targetGroup = listener.addTargets("targetGroup", AddApplicationTargetsProps.builder()
                .port(80)
                .targets(List.of(new InstanceTarget(ec2Instance, 80)))
                .build());

        new RecordSet(this, "loadBalancer", RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(loadBalancer)))
                .zone(hostedZone)
                .recordName(groupName + "-api.cloud-ha.com")
                .build());

        loadBalancer.getConnections().allowTo(ec2SecurityGroup, Port.tcp(80));

        String postgresUser = "master";
        String postgresPassword = "mastermaster";

        DatabaseInstance rds = new DatabaseInstance(this, "RDS-database", DatabaseInstanceProps.builder()
                .engine(DatabaseInstanceEngine.POSTGRES)
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .credentials(Credentials.fromPassword(postgresUser, SecretValue.unsafePlainText(postgresPassword)))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .removalPolicy(RemovalPolicy.DESTROY)
                .securityGroups(List.of(databaseSecurityGroup))
                .allocatedStorage(5)
                .build());

        rds.getConnections().allowFrom(ec2SecurityGroup, Port.tcp(5432));
        String databaseUrl = rds.getDbInstanceEndpointAddress();

        /*
        String.format("docker run -d " +
                        "-e DB_URL=%s " +
                        "-e DB_USERNAME=%s " +
                        "-e DB_PASSWORD=%s " +
                        "-e SPRING_PROFILES_ACTIVE=postgres " +
                        "--name my-application " +
                        "-p 80:8080 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest", databaseUrl, postgresUser, postgresPassword)
         */
        ec2Instance.addUserData("yum install docker -y",
                "sudo systemctl start docker",
                "aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin 292370674225.dkr.ecr.eu-north-1.amazonaws.com",
                "docker run -d -e DB_URL=christian-sederstrom-ec2-assig-rdsdatabase5490fe01-xni0yytzt3kk.chbvabsbak05.eu-north-1.rds.amazonaws.com -e DB_USERNAME=master -e DB_PASSWORD=mastermaster -e SPRING_PROFILES_ACTIVE=postgres --name my-application -p 80:8080 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest");

        // Trying to troubleshoot by outputting some information
        new CfnOutput(this, "-ec2-assignment-Output", CfnOutputProps.builder()
                .value(ec2Instance.getInstanceId())
                .description("EC2 id output")
                .build());

        new CfnOutput(this, "database-endpoint", CfnOutputProps.builder()
                .value(databaseUrl)
                .description("Database Endpoint: ")
                .build());

        new CfnOutput(this, "securitygroup", CfnOutputProps.builder()
                .value(ec2SecurityGroup.getSecurityGroupId())
                .build());
    }
}