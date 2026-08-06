import React, { useState } from "react";
import { Form, Input, Button, Alert } from "antd";
import { UserOutlined, LockOutlined, MailOutlined, IdcardOutlined } from "@ant-design/icons";
import { login, register } from "./api";

/**
 * The sign-in / create-account panel.
 *
 * You only ever see this screen when there is no live session — App.js checks
 * /api/me first and goes straight to the account screen if one comes back. So
 * there is nothing to pre-fill here: a returning visitor with a valid session
 * never reaches this component at all.
 */
/**
 * Turns a failed request into something to put on screen.
 *
 * A device refusal is not the same kind of event as a typo in a password, and
 * showing them identically would be a small cruelty: one is "try again", the
 * other is "you cannot fix this yourself, go and find someone". So the two get
 * a warning rather than an error, a heading that names the situation, and the
 * server's own sentence underneath explaining what to do.
 *
 * The branch is on the `code` the server sent, never on the wording of the
 * message — prose gets reworded and translated, codes do not.
 */
function describe(err) {
  switch (err.code) {
    case "DEVICE_OWNED_BY_OTHER_USER":
      return {
        type: "warning",
        title: "This workstation belongs to someone else",
        text: err.message,
      };
    case "WORKER_BOUND_ELSEWHERE":
      return {
        type: "warning",
        title: "Your account is registered to another workstation",
        text: err.message,
      };
    default:
      return { type: "error", text: err.message };
  }
}

export default function AuthPanel({ onSignedIn }) {
  const [tab, setTab] = useState("signin");
  const [busy, setBusy] = useState(false);
  const [alert, setAlert] = useState(null); // { type, title, text }

  const [form] = Form.useForm();

  const switchTab = (next) => {
    setTab(next);
    setAlert(null);
    form.resetFields();
  };

  const handleSubmit = async (values) => {
    setBusy(true);
    setAlert(null);
    try {
      if (tab === "signin") {
        const data = await login(values);
        onSignedIn(data); // the cookie has just been written by Spring Boot
      } else {
        const data = await register(values);
        setAlert({ type: "success", text: data.message });
        setTab("signin");
        form.resetFields();
      }
    } catch (err) {
      setAlert(describe(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel">
      <div className="panel-body">
        <div className="header fade-up">
        <h1 className="title">
          Cookie<span>Shookie</span>
        </h1>
        <p className="subtitle">
          {tab === "signin"
            ? "Sign in to your account."
            : "Create an account to get started."}
        </p>
      </div>

      {/* ---- Sign in / Create account switch ---- */}
      <div className="segment">
        <button
          className={tab === "signin" ? "active" : ""}
          onClick={() => switchTab("signin")}
        >
          Sign in
        </button>
        <button
          className={tab === "register" ? "active" : ""}
          onClick={() => switchTab("register")}
        >
          Create account
        </button>
      </div>

      {alert && (
        <Alert
          type={alert.type}
          message={alert.title || alert.text}
          description={alert.title ? alert.text : undefined}
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {/* key={tab} rebuilds the form on switch, which replays the animation */}
      <div className="swap" key={tab}>
        <Form
          form={form}
          layout="vertical"
          requiredMark={false}
          onFinish={handleSubmit}
        >
          {tab === "register" && (
            <>
              <Form.Item
                name="fullName"
                label="Full name"
                rules={[{ required: true, message: "Please enter your name" }]}
              >
                <Input prefix={<IdcardOutlined />} placeholder="Tejas Jaiswal" />
              </Form.Item>

              <Form.Item
                name="email"
                label="Email"
                rules={[
                  { required: true, message: "Please enter your email" },
                  { type: "email", message: "That does not look like an email" },
                ]}
              >
                <Input prefix={<MailOutlined />} placeholder="tejas@example.com" />
              </Form.Item>
            </>
          )}

          <Form.Item
            name="username"
            label="Username"
            rules={[{ required: true, message: "Username is required" }]}
          >
            <Input prefix={<UserOutlined />} placeholder="tejas" />
          </Form.Item>

          <Form.Item
            name="password"
            label="Password"
            rules={[{ required: true, message: "Password is required" }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="••••••••" />
          </Form.Item>

          <Button type="primary" htmlType="submit" block loading={busy} style={{ marginTop: 4 }}>
            {tab === "signin" ? "Sign in" : "Create account"}
          </Button>
        </Form>
      </div>

      </div>
    </div>
  );
}
