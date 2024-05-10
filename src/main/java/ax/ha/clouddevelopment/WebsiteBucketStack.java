package ax.ha.clouddevelopment;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.iam.AnyPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.BucketWebsiteTarget;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.BucketDeploymentProps;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

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
        // S3 Bucket resource
        final Bucket bucket =
                Bucket.Builder.create(this, "websiteBucket")
                        .bucketName(groupName + ".cloud-ha.com")
                        .publicReadAccess(true)
                        .blockPublicAccess(BlockPublicAccess.BLOCK_ACLS)
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .autoDeleteObjects(true)
                        .websiteIndexDocument("index.html")
                        .build();

        PolicyStatement statement = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:GetObject"))
                .resources(List.of(bucket.getBucketArn() + "/*"))
                .principals(List.of(new AnyPrincipal()))
                .conditions(Map.of("IpAddress", Map.of("aws:SourceIp", List.of("192.168.1.130/32"))))
                .build();

        bucket.addToResourcePolicy(statement);

        // Trying to add the website folder
        new BucketDeployment(this, "DeployWebsite", BucketDeploymentProps.builder()
                .sources(List.of(Source.asset("src/main/resources")))
                .destinationBucket(bucket)
                .build());

        new RecordSet(this, "RecordSet", RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(RecordTarget.fromAlias(new BucketWebsiteTarget(bucket)))
                .zone(HostedZone.fromHostedZoneAttributes(this, "hostedZone", HostedZoneAttributes.builder()
                        .zoneName("cloud-ha.com")
                        .hostedZoneId("Z0413857YT73A0A8FRFF")
                        .build()))
                .recordName(groupName + ".cloud-ha.com")
                .build());


        // Bucket ARN CfnOutput variable. This is helpful for a later stage when simply wanting
        // to refer to your storage bucket using only a simple variable
        CfnOutput.Builder.create(this, "websiteBucketOutput")
                .description(String.format("URL of your bucket.", groupName))
                .value(bucket.getBucketWebsiteUrl())
                .exportName(groupName + "-s3-assignment-url")
                .build();
    }
}