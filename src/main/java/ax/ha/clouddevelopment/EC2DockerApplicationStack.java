package ax.ha.clouddevelopment;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.InstanceTarget;
import software.amazon.awscdk.services.iam.IManagedPolicy;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;

import static software.amazon.awscdk.services.ec2.InstanceClass.BURSTABLE3;
import static software.amazon.awscdk.services.ec2.InstanceSize.MICRO;

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

        final SecurityGroup securityGroup = SecurityGroup.Builder.create(this, "securityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .securityGroupName("super-secure")
                .build();

        // Defining the policies here for readability and i think it makes it easier to change in the future
        final List<IManagedPolicy> managedPolicies = Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly"));

        Role.Builder.create(this, "EC2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(managedPolicies)
                .build();

        final Instance ec2Instance = new Instance(this, "kicke-EC2", InstanceProps.builder()
                .vpc(vpc)
                .instanceName(groupName + "-EC2-server")
                .instanceType(InstanceType.of(BURSTABLE3, MICRO))
                .machineImage(AmazonLinuxImage.Builder.create()
                        .generation(AmazonLinuxGeneration.AMAZON_LINUX_2)
                        .cpuType(AmazonLinuxCpuType.X86_64)
                        .build())
                .securityGroup(securityGroup)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)
                        .build())
                .build());

        ec2Instance.addUserData(
                "yum install docker -y",
                "sudo systemctl start docker",
                "aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin 292370674225.dkr.ecr.eu-north-1.amazonaws.com",
                "docker run -d --name my-application -p 80:8080 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest"
        );

        final ApplicationLoadBalancer loadBalancer = new ApplicationLoadBalancer(this, "applicationLoadBalancer",
                ApplicationLoadBalancerProps.builder()
                        .vpc(vpc)
                        .vpcSubnets(SubnetSelection.builder()
                                .subnetType(SubnetType.PUBLIC)
                                .build())
                        .internetFacing(true)
                        .build());

        ApplicationListener listener = loadBalancer.addListener("listener", BaseApplicationListenerProps.builder()
                .protocol(ApplicationProtocol.HTTP)
                .open(true)
                .build());

        listener.addTargets("targetGroup", AddApplicationTargetsProps.builder()
                .port(80)
                .targets(List.of(new InstanceTarget(ec2Instance, 80)))
                .build());

        new RecordSet(this, "loadBalancer", RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(loadBalancer)))
                .zone(hostedZone)
                .recordName(groupName + "-api.cloud-ha.com")
                .build());

        loadBalancer.getConnections().allowTo(securityGroup, Port.tcp(80));

        // Trying to troubleshoot by outputting some information
        // TODO the target group is unhealthy, figure out why!!!!!!!!!!!!!!!!
        new CfnOutput(this, "TargetGroupInfo", CfnOutputProps.builder()
                .value(loadBalancer.getLoadBalancerDnsName())
                .description("Whats going on?!?! Whats going on?!?!")
                .build());

    }
}