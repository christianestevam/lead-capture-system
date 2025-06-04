package com.forrestgump.leadprocessor.domain.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;

import java.time.Instant;

@DynamoDbBean
public class Lead {
    private String leadId;
    private String cpf; // Input CPF before encryption
    private String encryptedCpf; // Stored encrypted CPF
    private String salt;
    private String name;
    private String phone;
    private String email;
    private Instant createdAt;

    // Default constructor required by DynamoDb Enhanced Client
    public Lead() {
    }

    public Lead(String leadId, String cpf, String encryptedCpf, String salt, String name, String phone, String email, Instant createdAt) {
        this.leadId = leadId;
        this.cpf = cpf;
        this.encryptedCpf = encryptedCpf;
        this.salt = salt;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.createdAt = createdAt;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("leadId")
    public String getLeadId() {
        return leadId;
    }

    public void setLeadId(String leadId) {
        this.leadId = leadId;
    }

    @DynamoDbAttribute("cpf")
    public String getCpf() {
        return cpf;
    }

    public void setCpf(String cpf) {
        this.cpf = cpf;
    }

    @DynamoDbAttribute("encryptedCpf")
    public String getEncryptedCpf() {
        return encryptedCpf;
    }

    public void setEncryptedCpf(String encryptedCpf) {
        this.encryptedCpf = encryptedCpf;
    }

    @DynamoDbAttribute("salt")
    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    @DynamoDbAttribute("name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @DynamoDbAttribute("phone")
    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @DynamoDbAttribute("email")
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}