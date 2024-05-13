package ax.ha.clouddevelopment;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.AnyPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.BucketWebsiteTarget;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
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


        // Bucket ARN CfnOutput variable. This is helpful for a later stage when simply wanting
        // to refer to your storage bucket using only a simple variable
        CfnOutput.Builder.create(this, "websiteBucketOutput")
                .description(String.format("URL of your bucket.", groupName))
                .value(websiteBucketbucket.getBucketWebsiteUrl())
                .exportName(groupName + "-s3-assignment-url")
                .build();

        // Another bucket for static content
        final Bucket staticContentBucket = new Bucket(this, "staticContentBucket",
                BucketProps.builder()
                        .bucketName(groupName + "-static-content")
                        .publicReadAccess(true)
                        .blockPublicAccess(BlockPublicAccess.BLOCK_ACLS)
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .autoDeleteObjects(true)
                        .build());

        // A new BucketDeployment for the static content folder
        new BucketDeployment(this, "DeployStaticContent", BucketDeploymentProps.builder()
                .sources(List.of(Source.asset("src/main/resources/static-content")))
                .destinationBucket(staticContentBucket)
                .build());

        // Output for the new bucket
        CfnOutput.Builder.create(this, "staticContentBucketOutput")
                .description("URL of the static content bucket.")
                .value(staticContentBucket.getBucketWebsiteUrl())
                .exportName(groupName + "-static-content-url")
                .build();
    }
}