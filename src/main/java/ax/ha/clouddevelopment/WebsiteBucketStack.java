package ax.ha.clouddevelopment;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudfront.*;
import software.amazon.awscdk.services.cloudfront.origins.S3Origin;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.BucketWebsiteTarget;
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.BucketDeploymentProps;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WebsiteBucketStack extends Stack {

    /**
     * Creates a CloudFormation stack for a simple S3 bucket used as a website
     */
    public WebsiteBucketStack(final Construct scope,
                              final String id,
                              final StackProps props,
                              final String groupName) {
        super(scope, id, props);
        // S3 Bucket resource for the website content
        final Bucket websiteBucketbucket = new Bucket(this, "websiteBucket",
                BucketProps.builder()
                        .bucketName(groupName + ".cloud-ha.com")
                        .publicReadAccess(true)
                        .blockPublicAccess(BlockPublicAccess.BLOCK_ACLS)
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .autoDeleteObjects(true)
                        .websiteIndexDocument("index.html")
                        .build());

        PolicyStatement statement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:GetObject"))
                .resources(List.of(websiteBucketbucket.getBucketArn() + "/*"))
                .principals(List.of(new AnyPrincipal()))
                .conditions(Map.of("IpAddress", Map.of("aws:SourceIp", List.of("192.168.1.130/32"))))
                .build();

        websiteBucketbucket.addToResourcePolicy(statement);

        // Trying to add the website folder
        new BucketDeployment(this, "DeployWebsite", BucketDeploymentProps.builder()
                .sources(List.of(Source.asset("src/main/resources/website")))
                .destinationBucket(websiteBucketbucket)
                .build());

        new RecordSet(this, "RecordSet", RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(RecordTarget.fromAlias(new BucketWebsiteTarget(websiteBucketbucket)))
                .zone(HostedZone.fromHostedZoneAttributes(this, "hostedZone", HostedZoneAttributes.builder()
                        .zoneName("cloud-ha.com")
                        .hostedZoneId("Z0413857YT73A0A8FRFF")
                        .build()))
                .recordName(groupName + ".cloud-ha.com")
                .build());

        // Bucket for storing the access-logs
        final Bucket accessLogBucket = new Bucket(this, "accessLogBucket",
                BucketProps.builder()
                        .bucketName(groupName + "-access-logs")
                        .publicReadAccess(false)
                        .blockPublicAccess(BlockPublicAccess.BLOCK_ACLS)
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .autoDeleteObjects(true)
                        .build());

        // Bucket for static content
        final Bucket staticContentBucket = new Bucket(this, "staticContentBucket",
                BucketProps.builder()
                        .bucketName(groupName + "-static-content")
                        .publicReadAccess(true)
                        .blockPublicAccess(BlockPublicAccess.BLOCK_ACLS)
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .autoDeleteObjects(true)
                        .serverAccessLogsBucket(accessLogBucket)
                        .serverAccessLogsPrefix("logs")
                        .build());


        // Needed to create an IVpc instead of Vpc since fromVpcAttributes returns IVpc.
        // Should be okay because the .vpc in security-group accepts anything that implements the IVpc interface.
        // Weird, i have to specify availabilityZones even though im referencing an already existing Vpc
        IVpc vpc = Vpc.fromVpcAttributes(this, "MyVpc", VpcAttributes.builder()
                .availabilityZones(List.of("eu-north-1")) // Just guessing that this is valid
                .vpcId("vpc-5e8e3b37")
                .build());

        final SecurityGroup securityGroup = SecurityGroup.Builder.create(this, "securityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .securityGroupName("super-secure")
                .build();

        // Defining the policies here for readability and i think it makes it easier to change in the future
        final List<IManagedPolicy> managedPolicies = Arrays.asList(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly"));

        final Role iamRole = Role.Builder.create(this, "IAMRole")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(managedPolicies)
                .build();

        // A new BucketDeployment for the static content folder
        new BucketDeployment(this, "DeployStaticContent", BucketDeploymentProps.builder()
                .sources(List.of(Source.asset("src/main/resources/static-content")))
                .destinationBucket(staticContentBucket)
                .build());

        // Creating a list of countries to block
        GeoRestriction restriction = GeoRestriction.denylist("SE");

////        // For testing purpose
//        GeoRestriction restriction = GeoRestriction.allowlist("FI", "AX", "SE");

        // Need to create an instance of the Distribution class to be able to access
        // the distribution domain name in the CloufFront output.
        Distribution distro = Distribution.Builder.create(this, "distro")
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(new S3Origin(staticContentBucket))
                        .build())
                .priceClass(PriceClass.PRICE_CLASS_100)
                .geoRestriction(restriction)
                .build();

        // Output for the static-content bucket
        CfnOutput.Builder.create(this, "staticContentBucketOutput")
                .description("URL of the static content bucket.")
                .value(distro.getDistributionDomainName())
                .exportName(groupName + "-static-content-url")
                .build();

        // Bucket ARN CfnOutput variable. This is helpful for a later stage when simply wanting
        // to refer to your storage bucket using only a simple variable
        CfnOutput.Builder.create(this, "websiteBucketOutput")
                .description(String.format("URL of your bucket.", groupName))
                .value(websiteBucketbucket.getBucketWebsiteUrl())
                .exportName(groupName + "-s3-assignment-url")
                .build();
    }
}